package dev.kestrel.conversation.store

import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.ConversationSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.IdentityHashMap

/** What the store writes into the activity log, by its kind only (design 9.2 of sub-project 7): never an id, a word or a byte. */
enum class ArchiveNote {
    /** A conversation's payload failed its check or did not decode, so the store deleted it. */
    CONVERSATION_DELETED,

    /** The index of past conversations was missing, unreadable, in another format or out of step, so the store rebuilt it. */
    INDEX_REBUILT,

    /**
     * The index of past conversations could not be read or rebuilt, so the list and the search cannot show past
     * conversations now; the store tries again later. Noted once until the index is ready again.
     */
    INDEX_FAILED,
}

/**
 * The conversations as JSON behind [archive] (design 9.1 and 9.2 of sub-project 7): one row per conversation, whose payload
 * is its snapshot, and the index of past conversations ([PastIndex]) in rows of its own, one per shard. Its first call reads
 * the open conversation, one row, and starts reading the index in [scope], so that a restore never waits for the index.
 * The store keeps the open conversation and the index in memory; a closed conversation's body is read only when a caller
 * asks for it. Whether a conversation is open is its row's [ArchiveHeader.open], never its payload's; writing an open
 * conversation closes the others, in the table and in memory alike.
 *
 * A save of the open conversation writes its row only. Every change of the closed conversations writes their rows and the
 * index's changed shards in one transaction. No change waits for the index: while it is read, a change writes its rows
 * alone and its update waits, and once the shards are decoded, the store reads the table's stamp under its lock, applies
 * the updates and checks them against it, and writes the shards that they changed. A process that ends in between leaves
 * an index that is out of step, which the next start rebuilds.
 * A row whose payload fails its check, or does not decode, is deleted with its index entry and noted by its kind only; a
 * row in a format that this version cannot read is kept for a later version, and the index counts it.
 *
 * When the index is missing while closed rows exist, cannot be read, is in another format or its stamp differs from the
 * table's, the store rebuilds it from the bodies in [scope], page by page, folding each body into the new index as it is
 * read: about 5 s on the A71 for 30 days (design R3). Meanwhile the open conversation is read and saved as usual; every
 * other change deletes the old index in its transaction and is replayed onto the rebuilt one; [searchable] says that the
 * index is being rebuilt; [list] waits for it; and [purge] deletes by the table's own times. When the index can be neither
 * read nor rebuilt, the store notes it once ([ArchiveNote.INDEX_FAILED]), [list] throws [PastUnavailableException],
 * [searchable] says so, and the store tries again after a minute, then after twice as long each time, up to an hour.
 *
 * The memory changes only after the archive's call completed. A call that did not complete, cancelled too, may still have
 * changed the archive, so the memory is dropped and the next call reads the archive again; an index that has failed stays
 * failed until its next try, because it holds nothing of the archive. A conversation that this process
 * deleted is never written again, also when its deletion did not complete, because the owner asked for it (design 9.7): an
 * older snapshot that a holder saves, or closes in [reopen], after a deletion of the conversation is left out, and when the
 * store reads the archive again and finds such a conversation, it deletes it again before it shows anything. "Delete
 * history" ([deleteAll]) works on the archive alone, every row and the index in one transaction and then the key, so it has
 * one outcome whatever the store holds: once the archive's deletion has started, a cancellation cannot split it from the
 * marking of the deleted conversations, so that after it no snapshot of a conversation that this process held is written
 * again; when the archive's call fails, it marks nothing, and every conversation stays as the archive has it.
 */
class ArchivedConversationStore(
    private val archive: RecordArchive,
    private val scope: CoroutineScope,
    private val noted: (ArchiveNote) -> Unit,
) : ConversationStore {
    /** The open conversation as the table has it: its body, or the header of an open row that this version cannot read. */
    private class Opened(val snapshot: ConversationSnapshot?, val unknown: ArchiveHeader?)

    private sealed interface Past {
        class Ready(val index: PastIndex) : Past

        /** Work on the index in [scope]; [done] completes when the work has ended or was stopped. */
        sealed class Working : Past {
            val done = CompletableDeferred<Unit>()
            lateinit var job: Job
        }

        /**
         * The stored shards are read and decoded. Changes meanwhile write their rows alone, and their updates wait in
         * [pending], to be applied to the decoded index, which the table's stamp then checks.
         */
        class Loading : Working() {
            val pending = mutableListOf<(PastIndex) -> PastIndex>()
        }

        /**
         * A rebuild reads the bodies. The changes made meanwhile wait in [pending], to be replayed onto its result, and
         * [touched] names their rows, which the rebuild never deletes.
         */
        class Rebuilding : Working() {
            val pending = mutableListOf<(PastIndex) -> PastIndex>()
            val touched = mutableSetOf<String>()
        }

        /** The index could not be read or rebuilt; [retry] reads it again after a wait. */
        class Failed(val cause: Throwable) : Past {
            var retry: Job? = null
        }
    }

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val mutex = Mutex()

    // Both are null until a call reads the archive, and again after a change that did not complete, under the lock; a
    // failed index stays with its next try (forget).
    private var opened: Opened? = null
    private var past: Past? = null

    // Every conversation that this process read from the archive or wrote to it, so that a snapshot of it may be held
    // outside the store; and those that it deleted, one by one or by a "delete history" that completed, which it never
    // writes or shows again.
    private val held = mutableSetOf<ConversationId>()
    private val deleted = mutableSetOf<ConversationId>()

    // The wait before the next try after the index failed, and whether the failure was noted since the index was last ready.
    private var retryMillis = FIRST_RETRY_MILLIS
    private var failureNoted = false

    @Volatile
    override var generation = 0L
        private set

    override suspend fun open(): ConversationSnapshot? = mutex.withLock { loaded().snapshot }

    override suspend fun save(snapshot: ConversationSnapshot) = mutex.withLock { write(listOf(snapshot).filter { it.id !in deleted }) }

    override suspend fun list(): List<ConversationSummary> = withIndex { index ->
        index.conversations.map { ConversationSummary(it.id, it.openedAtWall, it.lastWall, it.commands, it.firstRequest) }
    }

    override suspend fun get(id: ConversationId): ConversationSnapshot? = getAll(listOf(id))[id]

    override suspend fun getAll(ids: Collection<ConversationId>): Map<ConversationId, ConversationSnapshot> = mutex.withLock { bodies(ids) }

    override suspend fun reopen(id: ConversationId, closing: ConversationSnapshot?, nowWall: Long, testMode: Boolean): ConversationSnapshot? =
        mutex.withLock {
            if (loaded().snapshot?.id == id) return@withLock null
            val chosen = bodies(listOf(id))[id] ?: return@withLock null
            val opened = chosen.copy(open = true, testMode = testMode, continuedAtWall = nowWall, lastWall = nowWall)
            // Writing the opened one closes the conversation that was open, as the table does (D23).
            write(listOfNotNull(closing?.copy(open = false)).filter { it.id !in deleted } + opened)
            opened
        }

    override suspend fun delete(id: ConversationId) = mutex.withLock {
        deleted += id
        val before = loaded()
        val wasOpen = before.snapshot?.id == id
        change(gone = setOf(id.value), update = if (wasOpen) null else ({ index -> index.without(setOf(id.value)) })) {
            if (wasOpen) opened = Opened(null, before.unknown)
        }
    }

    /**
     * Deletes every conversation in the archive alone (design 9.7). Once it has the lock, the archive's deletion and the
     * marking of the deleted conversations run as one unit that cancellation cannot split, the generation's raise first; a
     * caller's cancellation takes effect after it. When the archive's call fails, it marks nothing.
     */
    override suspend fun deleteAll() = mutex.withLock {
        withContext(NonCancellable) {
            generation++
            stopWork()
            try {
                archive.deleteAll()
            } catch (e: Throwable) {
                forget()
                throw e
            }
            // Only now that every row is gone: a snapshot that a holder keeps of one of them is never written back.
            deleted += held
            (past as? Past.Failed)?.retry?.cancel()
            opened = Opened(null, null)
            past = ready(PastIndex.EMPTY)
        }
    }

    override suspend fun purge(retentionDays: Int, nowWall: Long): Int = mutex.withLock {
        loaded()
        val oldest = nowWall - retentionDays * DAY_MILLIS
        val newest = nowWall + DAY_MILLIS
        changed(update = { index -> index.without(index.outside(oldest, newest)) }, touched = emptySet(), inMemory = {}) { index ->
            archive.purge(oldest, newest, index)
        }
    }

    override suspend fun searchable(): Searchable {
        while (true) {
            val loading = mutex.withLock {
                val open = loaded().snapshot
                when (val state = settle()) {
                    is Past.Ready -> return Searchable.Ready(state.index, open)
                    is Past.Rebuilding -> return Searchable.Rebuilding
                    is Past.Failed -> return Searchable.Failed
                    is Past.Loading -> state
                }
            }
            loading.done.await()
        }
    }

    /**
     * What [kept], snapshots to store, do to the index: every conversation that closes enters it, one that opens leaves it,
     * and an open row that a written open one replaces closes as the table closes it: its body enters the index, or a row
     * that this version cannot read is kept. Null when the index does not change: a save of the open conversation alone.
     */
    private fun indexUpdate(kept: List<ConversationSnapshot>, before: Opened): ((PastIndex) -> PastIndex)? {
        if (kept.isEmpty()) return null
        val written = kept.mapTo(HashSet()) { it.id }
        val opening = kept.lastOrNull { it.open }
        val replaced = before.snapshot?.takeIf { opening != null && it.id !in written }?.copy(open = false)
        val replacedUnknown = before.unknown?.takeIf { opening != null && it.id != opening.id.value }
        val closing = kept.filter { !it.open } + listOfNotNull(replaced)
        if (closing.isEmpty() && replacedUnknown == null && (opening == null || opening.id == before.snapshot?.id)) return null
        return { index ->
            index.without(setOfNotNull(opening?.id?.value)).with(closing)
                .keeping(listOfNotNull(replacedUnknown?.let { KeptRow(it.id, it.lastMillis) }))
        }
    }

    /** Stores [kept], snapshots of conversations that this process did not delete, in one transaction. */
    private suspend fun write(kept: List<ConversationSnapshot>) {
        if (kept.isEmpty()) return
        val before = loaded()
        val written = kept.mapTo(HashSet()) { it.id }
        val opening = kept.lastOrNull { it.open }
        held += written
        change(rows = kept.map(::row), update = indexUpdate(kept, before)) {
            val stillOpen = before.snapshot?.takeIf { opening == null && it.id !in written }
            opened = Opened(opening ?: stillOpen, before.unknown.takeIf { opening == null })
        }
    }

    /** [rows] stored and [gone] deleted in one transaction, with the index changed by [update] when it is set. */
    private suspend fun change(rows: List<ArchiveRow> = emptyList(), gone: Set<String> = emptySet(), update: ((PastIndex) -> PastIndex)?, inMemory: () -> Unit) =
        changed(update, rows.map { it.header.id } + gone, inMemory) { index -> archive.write(ArchiveChange(rows, gone, index)) }

    /**
     * The one way to change the archive: [apply] makes the change, in one transaction with the index's write that it is
     * given. While the index is ready, that write stores the shards that [update] changed. While the index is rebuilt or
     * has failed, it deletes every shard, so that the old index can never match the table again; during a rebuild, [update]
     * waits to be replayed onto the rebuilt index, and [touched] names the rows that the rebuild must not delete. While the
     * index is read, the shards stay as they are, and [update] waits to be applied to the index once it is decoded. Only
     * once the archive's call completed does [inMemory] change the memory; when it did not complete, for any reason,
     * cancellation too, the memory is dropped.
     */
    private suspend fun <T> changed(update: ((PastIndex) -> PastIndex)?, touched: Collection<String>, inMemory: () -> Unit, apply: suspend (IndexWrite) -> T): T {
        val state = past
        var next: PastIndex? = null
        val index = when {
            update == null || state is Past.Loading -> IndexWrite.Keep
            state is Past.Ready -> update(state.index).let { changed ->
                next = changed
                written(changed.changesFrom(state.index))
            }
            else -> IndexWrite.Replace(emptyMap())
        }
        val result = try {
            apply(index)
        } catch (e: Throwable) {
            forget()
            throw e
        }
        next?.let { past = ready(it) }
        if (update != null && state is Past.Rebuilding) {
            state.pending += update
            state.touched += touched
        }
        if (update != null && state is Past.Loading) state.pending += update
        inMemory()
        return result
    }

    /** Runs [block] under the lock with the ready index; while the index is read or rebuilt, it waits without the lock. */
    private suspend fun <T> withIndex(block: suspend (PastIndex) -> T): T {
        while (true) {
            val working = mutex.withLock {
                loaded()
                when (val state = settle()) {
                    is Past.Ready -> return block(state.index)
                    is Past.Working -> state
                    is Past.Failed -> throw PastUnavailableException(state.cause)
                }
            }
            working.done.await()
        }
    }

    /**
     * The open conversation, once the archive has been read: the first call reads the open row, one decryption, and starts
     * reading the index in [scope], unless the index has failed and waits for its next try. An open row that fails its
     * check or does not decode is deleted and noted; one that this process deleted is deleted again.
     */
    private suspend fun loaded(): Opened {
        opened?.let { return it }
        val entry = archive.readOpen()?.let(::checked)
        var snapshot: ConversationSnapshot? = null
        var unknown: ArchiveHeader? = null
        when (entry) {
            null -> Unit
            is ArchiveEntry.Unknown -> unknown = entry.header
            is ArchiveEntry.Unreadable -> Unit
            is ArchiveEntry.Readable -> snapshot = decoded(entry)?.takeIf { it.id !in deleted }
        }
        opened = Opened(snapshot, unknown)
        if (past == null) past = loading()
        snapshot?.let { held += it.id }
        if (entry != null && unknown == null && snapshot == null) {
            change(gone = setOf(entry.header.id), update = null) {}
            // An id is compared as text: a row that Kestrel did not write may hold one that is not a conversation's id.
            if (deleted.none { it.value == entry.header.id }) noted(ArchiveNote.CONVERSATION_DELETED)
        }
        return checkNotNull(opened)
    }

    // The index's state, once a job of [scope] that ended without its result, because the scope ended first, has failed.
    private fun settle(): Past {
        val state = checkNotNull(past)
        if (state is Past.Working && state.job.isCompleted) return failed(IllegalStateException("The store's scope has ended")).also { past = it }
        return state
    }

    private fun ready(index: PastIndex): Past.Ready {
        index.conversations.mapTo(held) { it.id }
        retryMillis = FIRST_RETRY_MILLIS
        failureNoted = false
        return Past.Ready(index)
    }

    private fun loading(): Past.Loading = Past.Loading().also { state -> start(state) { load(state) } }

    private fun rebuilding(): Past.Rebuilding = Past.Rebuilding().also { state -> start(state) { rebuild(state) } }

    private fun start(state: Past.Working, work: suspend () -> Unit) {
        state.job = scope.launch(start = CoroutineStart.LAZY) { work() }
        state.job.invokeOnCompletion { state.done.complete(Unit) }
        state.job.start()
    }

    /** Notes the failure once, and reads the index again after a wait that doubles at each failure, up to an hour. */
    private fun failed(cause: Throwable): Past.Failed {
        val state = Past.Failed(cause)
        if (!failureNoted) {
            failureNoted = true
            noted(ArchiveNote.INDEX_FAILED)
        }
        val wait = retryMillis
        retryMillis = minOf(retryMillis * 2, MAX_RETRY_MILLIS)
        state.retry = scope.launch {
            delay(wait)
            mutex.withLock { if (past === state) past = loading() }
        }
        return state
    }

    /**
     * Reads the index's shards and decodes them, outside the lock: no change writes a shard while the index is read. Then,
     * under the lock, it reads the table's stamp, which every change made meanwhile has changed already, applies their
     * updates and checks the result against the stamp. A usable index becomes ready, and the shards that the updates
     * changed are written, with the conversations that this process deleted deleted again, in one transaction. An index
     * that is missing while closed rows exist, unreadable, in another format or out of step is rebuilt; a read that fails
     * fails.
     */
    private suspend fun load(state: Past.Loading) {
        val shards = try {
            archive.readShards()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutex.withLock { if (past === state) past = failed(e) }
            return
        }
        val stored = if (shards.isEmpty()) PastIndex.EMPTY else decodedIndex(shards)
        mutex.withLock {
            if (past !== state) return
            val stamp = try {
                archive.readStamp()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                past = failed(e)
                return
            }
            val replayed = stored?.let { state.pending.fold(it) { index, update -> update(index) } }
            if (stored == null || replayed == null || replayed.stamp != stamp) {
                past = rebuilding()
                return
            }
            val again = deleted.filter { it in replayed }.mapTo(HashSet()) { it.value }
            past = ready(stored)
            if (replayed === stored && again.isEmpty()) return
            try {
                change(gone = again, update = { replayed.without(again) }) {}
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The memory is dropped, and the next call reads the archive again.
            }
        }
    }

    /**
     * Rebuilds the index from the bodies (design 9.2): the closed rows are read in pages of [REBUILD_PAGE], outside the
     * lock, so that the open conversation is saved meanwhile, and each body is folded into the new index as soon as it is
     * read, so that the rebuild never holds more than a page of bodies. The new shards are encoded outside the lock too.
     * The changes made meanwhile are replayed onto the result, which goes into the archive with the deletion of the rows that
     * failed, unless a change rewrote them, and of the conversations that this process deleted.
     */
    private suspend fun rebuild(state: Past.Rebuilding) {
        val builder = PastIndex.Builder()
        val failed = HashSet<String>()
        try {
            for (page in archive.closedIds().chunked(REBUILD_PAGE)) {
                for (entry in archive.read(page).map(::checked)) {
                    if (entry.header.open) continue
                    when (entry) {
                        is ArchiveEntry.Unknown -> builder.keep(KeptRow(entry.header.id, entry.header.lastMillis))
                        is ArchiveEntry.Unreadable -> failed += entry.header.id
                        is ArchiveEntry.Readable -> decoded(entry)?.let(builder::add) ?: failed.add(entry.header.id)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutex.withLock { if (past === state) past = failed(e) }
            return
        }
        val built = builder.build()
        // Encoded outside the lock; under it, only the shards that the replayed changes touched are encoded again.
        val bytes = IdentityHashMap<IndexShard, ByteArray>().apply { built.shards.forEach { put(it, PastIndexCodec.encode(it)) } }
        val dropped = mutex.withLock {
            if (past !== state) return
            val replayed = state.pending.fold(built) { index, update -> update(index) }
            val again = deleted.filter { it in replayed }.mapTo(HashSet()) { it.value }
            val index = replayed.without(again)
            val gone = failed - state.touched
            try {
                val shards = index.shards.associate { it.number.toString() to (bytes[it] ?: PastIndexCodec.encode(it)) }
                archive.write(ArchiveChange(deleted = gone + again, index = IndexWrite.Replace(shards)))
            } catch (e: CancellationException) {
                past = failed(e)
                throw e
            } catch (e: Exception) {
                // The write may have been made: the next try reads the archive again.
                past = failed(e)
                return
            }
            past = ready(index)
            val deletedHere = deleted.mapTo(HashSet()) { it.value }
            gone.count { it !in deletedHere }
        }
        repeat(dropped) { noted(ArchiveNote.CONVERSATION_DELETED) }
        noted(ArchiveNote.INDEX_REBUILT)
    }

    /**
     * The conversations [ids] that exist and that this process did not delete: the open one from memory, the others read
     * from their rows in one call. A row that fails its check, or does not decode, is deleted with its index entry and noted.
     */
    private suspend fun bodies(ids: Collection<ConversationId>): Map<ConversationId, ConversationSnapshot> {
        val open = loaded().snapshot
        val wanted = ids.filterTo(LinkedHashSet()) { it !in deleted }
        val found = LinkedHashMap<ConversationId, ConversationSnapshot>()
        open?.takeIf { it.id in wanted }?.let { found[it.id] = it }
        val closed = wanted.filter { it != open?.id }
        if (closed.isEmpty()) return found
        val failed = HashSet<String>()
        for (entry in archive.read(closed.map { it.value }).map(::checked)) {
            when (entry) {
                is ArchiveEntry.Unknown -> Unit
                is ArchiveEntry.Unreadable -> failed += entry.header.id
                is ArchiveEntry.Readable -> {
                    val snapshot = decoded(entry)
                    if (snapshot == null) failed += entry.header.id else found[snapshot.id] = snapshot
                }
            }
        }
        held += found.keys
        if (failed.isNotEmpty()) {
            change(gone = failed, update = { it.without(failed) }) {}
            repeat(failed.size) { noted(ArchiveNote.CONVERSATION_DELETED) }
        }
        return found
    }

    /**
     * The archive may hold a change that the memory does not: stops the work on the index, and drops the memory. A failed
     * index stays failed with its next try, which reads the archive again, so that a change that keeps failing, on a full
     * disk, never makes the next call read the index or rebuild it before then.
     */
    private fun forget() {
        opened = null
        if (past is Past.Failed) return
        stopWork()
        past = null
    }

    private fun stopWork() {
        val state = past as? Past.Working ?: return
        state.job.cancel()
        state.done.complete(Unit)
    }

    private fun written(changes: ShardChanges): IndexWrite =
        if (changes.isEmpty) IndexWrite.Keep else IndexWrite.Update(encoded(changes.put), changes.gone.mapTo(HashSet()) { it.toString() })

    private fun encoded(shards: List<IndexShard>): Map<String, ByteArray> = shards.associate { it.number.toString() to PastIndexCodec.encode(it) }

    // A row that this version cannot read is kept for a later version only when its id is a conversation's; any other comes
    // only from a write from outside Kestrel, and fails as a row that does not decode, so that a kept row's id is bounded.
    private fun checked(entry: ArchiveEntry): ArchiveEntry =
        if (entry is ArchiveEntry.Unknown && !isConversationId(entry.header.id)) ArchiveEntry.Unreadable(entry.header) else entry

    private fun isConversationId(id: String): Boolean = try {
        ConversationId(id)
        true
    } catch (e: IllegalArgumentException) {
        false
    }

    // The row's plain fields are the truth for whether the conversation is open and for its times, which the stamp and
    // retention count, so that a rebuilt index is always in step with the rows that it read.
    private fun decoded(entry: ArchiveEntry.Readable): ConversationSnapshot? = try {
        val header = entry.header
        json.decodeFromString(ConversationSnapshot.serializer(), entry.payload.decodeToString())
            .copy(open = header.open, openedAtWall = header.openedMillis, lastWall = header.lastMillis)
    } catch (e: IllegalArgumentException) {
        null
    }

    // The index from its shards' rows; null when a shard cannot be read, is named by no number, or does not decode.
    private fun decodedIndex(shards: List<ShardRead>): PastIndex? = try {
        val words = HashMap<String, String>()
        PastIndex.restored(
            shards.map { shard ->
                if (shard !is ShardRead.Readable) return null
                PastIndexCodec.decode(shard.name.toIntOrNull() ?: return null, shard.bytes, words)
            },
        )
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun row(snapshot: ConversationSnapshot): ArchiveRow {
        val header = ArchiveHeader(snapshot.id.value, snapshot.openedAtWall, snapshot.lastWall, snapshot.open, snapshot.records.size)
        return ArchiveRow(header, json.encodeToString(ConversationSnapshot.serializer(), snapshot).encodeToByteArray())
    }

    override fun toString(): String = "ArchivedConversationStore(open=${opened?.snapshot != null}, past=${past?.javaClass?.simpleName})"

    companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000

        /** How many closed rows a rebuild reads in one call: one thread hop per page, and never more than a page of bodies in memory. */
        const val REBUILD_PAGE = 50

        /** The first wait before the index is read again after it failed, and the longest. */
        const val FIRST_RETRY_MILLIS = 60_000L
        const val MAX_RETRY_MILLIS = 60 * 60_000L
    }
}
