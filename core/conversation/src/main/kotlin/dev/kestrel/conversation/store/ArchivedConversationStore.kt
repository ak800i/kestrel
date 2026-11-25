package dev.kestrel.conversation.store

import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.ConversationSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * The conversations as JSON behind [archive] (design 9.1 and 9.2 of sub-project 7): one row per conversation, whose
 * payload is its snapshot. Each row is read and decoded once per process and kept in memory, which every change updates,
 * so the list and the search never read a row twice. A row whose payload fails its tag, or does not decode, is deleted and
 * reported to [unreadable] by its kind only, as `SettingsStore` does with a setting. A row in a format that this version
 * cannot read ([ArchiveEntry.Unknown]) is left out and kept for a later version; the archive has reported it. Whether a
 * conversation is open is its row's [ArchiveHeader.open], never its payload's, so that the archive's table is the one
 * place that says it (plan 7C closes the other rows when it writes an open one). One call at a time.
 */
class ArchivedConversationStore(
    private val archive: RecordArchive,
    private val unreadable: (kind: String) -> Unit,
) : ConversationStore {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var known: MutableMap<ConversationId, ConversationSnapshot>? = null

    override suspend fun open(): ConversationSnapshot? = mutex.withLock { all().values.firstOrNull { it.open } }

    override suspend fun save(snapshot: ConversationSnapshot) = mutex.withLock {
        all()
        write(listOf(snapshot))
    }

    override suspend fun list(): List<ConversationSummary> = mutex.withLock {
        all().values.filter { !it.open }.sortedByDescending { it.lastWall }.map { snapshot ->
            ConversationSummary(snapshot.id, snapshot.openedAtWall, snapshot.lastWall, snapshot.records.size, snapshot.records.firstOrNull()?.request)
        }
    }

    override suspend fun get(id: ConversationId): ConversationSnapshot? = mutex.withLock { all()[id] }

    override suspend fun reopen(id: ConversationId, closing: ConversationSnapshot?, nowWall: Long, testMode: Boolean): ConversationSnapshot? =
        mutex.withLock {
            val chosen = all()[id]?.takeIf { !it.open } ?: return@withLock null
            val opened = chosen.copy(open = true, testMode = testMode, continuedAtWall = nowWall, lastWall = nowWall)
            // Every other open conversation closes too, so that at most one is open (D23).
            val closed = (all().values.filter { it.open && it.id != closing?.id } + listOfNotNull(closing)).map { it.copy(open = false) }
            write(closed + opened)
            opened
        }

    override suspend fun delete(id: ConversationId) = mutex.withLock {
        archive.delete(id.value)
        all().remove(id)
        Unit
    }

    override suspend fun deleteAll() = mutex.withLock {
        archive.deleteAll()
        known = mutableMapOf()
    }

    override suspend fun purge(retentionDays: Int, nowWall: Long): Int = mutex.withLock {
        val oldest = nowWall - retentionDays * DAY_MILLIS
        val newest = nowWall + DAY_MILLIS
        val deleted = archive.deleteOutside(oldest, newest)
        all().values.removeAll { !it.open && (it.lastWall < oldest || it.lastWall > newest) }
        deleted
    }

    override suspend fun closedSince(sinceWall: Long): List<ConversationSnapshot> = mutex.withLock {
        all().values.filter { !it.open && it.lastWall >= sinceWall }.sortedByDescending { it.lastWall }
    }

    private suspend fun write(snapshots: List<ConversationSnapshot>) {
        archive.write(snapshots.map { snapshot ->
            val header = ArchiveHeader(snapshot.id.value, snapshot.openedAtWall, snapshot.lastWall, snapshot.open, snapshot.records.size)
            ArchiveRow(header, json.encodeToString(ConversationSnapshot.serializer(), snapshot).encodeToByteArray())
        })
        val all = all()
        for (snapshot in snapshots) all[snapshot.id] = snapshot
    }

    private suspend fun all(): MutableMap<ConversationId, ConversationSnapshot> {
        known?.let { return it }
        val loaded = LinkedHashMap<ConversationId, ConversationSnapshot>()
        for (header in archive.headers()) decoded(archive.read(header.id))?.let { loaded[it.id] = it }
        known = loaded
        return loaded
    }

    private suspend fun decoded(entry: ArchiveEntry?): ConversationSnapshot? = when (entry) {
        null, is ArchiveEntry.Unknown -> null
        is ArchiveEntry.Unreadable -> dropped(entry.header.id)
        is ArchiveEntry.Readable -> try {
            json.decodeFromString(ConversationSnapshot.serializer(), entry.payload.decodeToString()).copy(open = entry.header.open)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            dropped(entry.header.id)
        }
    }

    private suspend fun dropped(id: String): ConversationSnapshot? {
        archive.delete(id)
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
