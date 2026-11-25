package dev.kestrel.conversation.store

import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.ConversationSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * The conversations as JSON behind [archive] (design 9.1 and 9.2 of sub-project 7): one row per conversation, whose
 * payload is its snapshot. Every row is read in one call to the archive and decoded once per process, at the store's
 * first use, and kept in memory, so the list and the search never read a row twice. A row whose payload fails its tag, or
 * does not decode, is deleted and reported to [unreadable] by its kind only, as `SettingsStore` does with a setting. A row
 * in a format that this version cannot read ([ArchiveEntry.Unknown]) is left out and kept for a later version; the
 * archive has reported it. Whether a conversation is open is its row's [ArchiveHeader.open], never its payload's, so that
 * the archive's table is the one place that says it; writing an open conversation closes the others, in the table and in
 * memory alike.
 *
 * Every change goes through [change], which changes the memory only after the archive's call completed. A call that did
 * not complete, cancelled too, may still have changed the archive, so the memory is dropped and the next call reads the
 * archive again. A conversation that this process deleted is never written again, also when its deletion did not
 * complete, because the owner asked for it (design 9.7): an older snapshot that a holder saves, or closes in [reopen],
 * after "delete history" or a deletion of the conversation is left out. One call at a time.
 */
class ArchivedConversationStore(
    private val archive: RecordArchive,
    private val unreadable: (kind: String) -> Unit,
) : ConversationStore {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var known: MutableMap<ConversationId, ConversationSnapshot>? = null

    // Every conversation that this process read from the archive or wrote to it, so that a snapshot of it may be held
    // outside the store; and those of them that it deleted, which it never writes again.
    private val held = mutableSetOf<ConversationId>()
    private val deleted = mutableSetOf<ConversationId>()

    @Volatile
    override var generation = 0L
        private set

    override suspend fun open(): ConversationSnapshot? = mutex.withLock { all().values.firstOrNull { it.open } }

    override suspend fun save(snapshot: ConversationSnapshot) = mutex.withLock { write(listOf(snapshot)) }

    override suspend fun list(): List<ConversationSummary> = mutex.withLock {
        all().values.filter { !it.open }.sortedByDescending { it.lastWall }.map { snapshot ->
            ConversationSummary(snapshot.id, snapshot.openedAtWall, snapshot.lastWall, snapshot.records.size, snapshot.records.firstOrNull()?.request)
        }
    }

    override suspend fun get(id: ConversationId): ConversationSnapshot? = mutex.withLock { all()[id] }

    override suspend fun reopen(id: ConversationId, closing: ConversationSnapshot?, nowWall: Long, testMode: Boolean): ConversationSnapshot? =
        mutex.withLock {
            val chosen = all()[id]?.takeIf { !it.open && id !in deleted } ?: return@withLock null
            val opened = chosen.copy(open = true, testMode = testMode, continuedAtWall = nowWall, lastWall = nowWall)
            // Writing the opened one closes every other open conversation too, so that at most one is open (D23).
            write(listOfNotNull(closing?.copy(open = false)) + opened)
            opened
        }

    override suspend fun delete(id: ConversationId) = mutex.withLock {
        change(deletes = setOf(id), inArchive = { archive.delete(id.value) }) { it.remove(id) }
    }

    override suspend fun deleteAll() = mutex.withLock {
        generation++
        change(deletes = held.toSet(), inArchive = { archive.deleteAll() }) { it.clear() }
    }

    override suspend fun purge(retentionDays: Int, nowWall: Long): Int = mutex.withLock {
        val oldest = nowWall - retentionDays * DAY_MILLIS
        val newest = nowWall + DAY_MILLIS
        change(inArchive = { archive.deleteOutside(oldest, newest) }) { memory ->
            memory.values.removeAll { !it.open && (it.lastWall < oldest || it.lastWall > newest) }
        }
    }

    override suspend fun closedSince(sinceWall: Long): List<ConversationSnapshot> = mutex.withLock {
        all().values.filter { !it.open && it.lastWall >= sinceWall }.sortedByDescending { it.lastWall }
    }

    private suspend fun write(snapshots: List<ConversationSnapshot>) {
        val kept = snapshots.filter { it.id !in deleted }
        if (kept.isEmpty()) return
        held += kept.map { it.id }
        val rows = kept.map { snapshot ->
            val header = ArchiveHeader(snapshot.id.value, snapshot.openedAtWall, snapshot.lastWall, snapshot.open, snapshot.records.size)
            ArchiveRow(header, json.encodeToString(ConversationSnapshot.serializer(), snapshot).encodeToByteArray())
        }
        change(inArchive = { archive.write(rows) }) { memory ->
            for (snapshot in kept) {
                if (snapshot.open) memory.replaceAll { _, other -> if (other.open) other.copy(open = false) else other }
                memory[snapshot.id] = snapshot
            }
        }
    }

    /**
     * The one way to change the conversations: [deletes] are marked as deleted before anything else, [inArchive] changes the
     * archive, and only once it has completed does [inMemory] change the memory the same way, if the memory is loaded. When
     * [inArchive] does not complete, for any reason, cancellation too, the memory is dropped.
     */
    private suspend fun <T> change(
        deletes: Set<ConversationId> = emptySet(),
        inArchive: suspend () -> T,
        inMemory: (MutableMap<ConversationId, ConversationSnapshot>) -> Unit,
    ): T {
        deleted += deletes
        val result = try {
            inArchive()
        } catch (e: Throwable) {
            known = null
            throw e
        }
        known?.let(inMemory)
        return result
    }

    private suspend fun all(): MutableMap<ConversationId, ConversationSnapshot> {
        known?.let { return it }
        val loaded = LinkedHashMap<ConversationId, ConversationSnapshot>()
        for (entry in archive.readAll()) decoded(entry)?.let { loaded[it.id] = it }
        held += loaded.keys
        known = loaded
        return loaded
    }

    private suspend fun decoded(entry: ArchiveEntry): ConversationSnapshot? = when (entry) {
        is ArchiveEntry.Unknown -> null
        is ArchiveEntry.Unreadable -> dropped(entry.header.id)
        is ArchiveEntry.Readable -> try {
            json.decodeFromString(ConversationSnapshot.serializer(), entry.payload.decodeToString()).copy(open = entry.header.open)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            dropped(entry.header.id)
        }
    }

    // While the memory loads, so there is no memory to change; no snapshot of the row was ever given out.
    private suspend fun dropped(id: String): ConversationSnapshot? {
        change(inArchive = { archive.delete(id) }) {}
        unreadable(KIND)
        return null
    }

    override fun toString(): String = "ArchivedConversationStore(known=${known?.size})"

    companion object {
        /** The kind that an unreadable row is reported by. */
        const val KIND = "conversation"

        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
