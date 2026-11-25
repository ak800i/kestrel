package dev.kestrel.privacy.conversations

import dev.kestrel.privacy.crypto.AesGcmFieldCipher
import dev.kestrel.privacy.crypto.ErasableKey
import dev.kestrel.privacy.crypto.KeyErasure
import dev.kestrel.privacy.db.ConversationDao
import dev.kestrel.privacy.db.ConversationIndexRow
import dev.kestrel.privacy.db.ConversationRow
import dev.kestrel.privacy.db.ConversationStamp
import dev.kestrel.privacy.db.IndexRowUpdate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.AEADBadTagException

/** A conversation to store: its plain fields and its payload, which the archive encrypts. toString prints only sizes. */
class PlainConversation(val header: ConversationHeader, val payload: ByteArray) {
    override fun toString(): String = "PlainConversation(header=$header, payload=${payload.size} bytes)"
}

/** A conversation as the archive reads it back. */
sealed interface StoredConversation {
    val header: ConversationHeader

    /** Its payload, decrypted. */
    class Readable(override val header: ConversationHeader, val payload: ByteArray) : StoredConversation {
        override fun toString(): String = "Readable(header=$header, payload=${payload.size} bytes)"
    }

    /** Its payload failed its check: it was moved from another row, damaged, or its key was erased. It can never be read. */
    class FailedCheck(override val header: ConversationHeader) : StoredConversation

    /** Its payload is in a format that this version of Kestrel cannot read; the row stays for a later version. */
    class UnknownFormat(override val header: ConversationHeader) : StoredConversation
}

/**
 * A shard of the index as the archive reads it back, by its row's name: its payload, decrypted, or the mark that it failed
 * its check or is in a cipher's format that this version cannot read, which plan 7B's store treats alike, by rebuilding
 * the index.
 */
sealed interface StoredShard {
    val name: String

    class Readable(override val name: String, val payload: ByteArray) : StoredShard {
        override fun toString(): String = "Readable(${payload.size} bytes)"
    }

    class Unreadable(override val name: String) : StoredShard {
        override fun toString(): String = "Unreadable"
    }
}

/**
 * What a change does to the index's shards, each a plain payload by its name that the archive encrypts: keeps them;
 * puts [Update.put] and deletes [Update.deleted]; or deletes them all and stores [Replace.shards].
 */
sealed interface IndexUpdate {
    data object Keep : IndexUpdate

    class Update(val put: Map<String, ByteArray>, val deleted: Set<String>) : IndexUpdate {
        override fun toString(): String = "Update(put=${put.size}, deleted=${deleted.size})"
    }

    class Replace(val shards: Map<String, ByteArray>) : IndexUpdate {
        override fun toString(): String = "Replace(${shards.size} shards)"
    }
}

/** One change, in one transaction: [conversations] stored, [deleted] deleted, and the index as [index] says. */
class ConversationChange(val conversations: List<PlainConversation>, val deleted: Collection<String>, val index: IndexUpdate) {
    override fun toString(): String = "ConversationChange(conversations=${conversations.size}, deleted=${deleted.size}, index=$index)"
}

/**
 * The archive of past conversations (design 9.1 and 9.2 of sub-project 7): one row per conversation, whose payload is
 * encrypted under its own erasable key and bound to the conversation's id, and the index of past conversations in rows of
 * their own, one per shard, encrypted under the same key and each bound to its name, so that no payload can be moved to
 * another row. It knows only bytes: `:app` gives it the conversation's JSON and plan 7B's encoded shards. Only the id, the
 * open and last-change times, whether the conversation is open and its number of commands are plain, for sorting and
 * retention.
 *
 * Every read runs its query and decrypts its rows on [dispatcher], on one thread, with no hop between the two, because
 * a Keystore operation after a hop costs about twice as much on the A71 (design R3): a restore is one Keystore
 * operation, the index one per shard, and a body one more. A read that meets a payload or a shard larger than a piece
 * of 512 KB is made again, on the same thread, in a transaction that reads the larger ones in pieces, because Android's
 * cursor window holds at most 2 MB of a row ([ConversationDao]); the rows come back from one version of the table, and
 * the decryption follows the transaction. The stamp is a plain query. A read stops between two decryptions when its
 * caller is cancelled. Reads take no guard, because they store nothing, so that a rebuild's long read never holds up a
 * write's encryption; a read's transaction of pieces holds up a save for a few ms per MB, and each decryption holds the
 * key's read lock, which a write's [ErasableKey.get] waits for. Writes and [deleteAll] run under the key's
 * [ErasableKey.guard], which every user of the key shares, so that [deleteAll] never erases the key between the
 * encryption of a write and its row. The functions are safe to call from the main thread.
 *
 * Only a write makes the key: a read decrypts each row with the key as it is ([ErasableKey.withExisting]), and the
 * key's erasure waits for a decryption that has started. So a read that overlaps [deleteAll] ("delete history") never
 * fails because the key went, and never makes a new key. [deleteAll] deletes the rows first and then erases the key, so
 * such a read sees each row as one of three: as it was; when its query ran before the deletion and the key went while
 * it decrypted, a conversation as [StoredConversation.FailedCheck] (one in a format that this version cannot read stays
 * [StoredConversation.UnknownFormat]) and a shard as [StoredShard.Unreadable]; or, when its query ran after the
 * deletion, not at all. Its caller orders the two (plan 7B's store does). A read with no key at all sees the same as in
 * the second case. An archive that "delete history" emptied never stores under its old key: the key object, one per
 * process ([ErasableKey.storing]), erases the key that a process which ended between the deletion and the erasure, or
 * an erasure that failed, left, before the next change stores anything. An archive that a purge or a deletion emptied
 * keeps its key until the next process's first change, which the purge job makes at every start.
 *
 * It deletes no row on its own: plan 7B's store deletes a conversation that fails its check, and reports it. A
 * conversation in a format that this version cannot read is kept, and [onUnknownFormat] hears of it once for this
 * archive object, of which PrivacyStore makes one; when [onUnknownFormat] throws, the read fails, and the next read of
 * the conversation reports it again. A second archive object of the same tables would report it again, which adds only
 * a line to the activity log.
 */
class ConversationArchiveStore(
    private val dao: ConversationDao,
    private val key: ErasableKey,
    private val onUnknownFormat: suspend () -> Unit = {},
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    // Writes encrypt with this cipher, which makes the key when there is none; reads decrypt with the key as it is.
    private val sealing = AesGcmFieldCipher { key.get() }

    // The ids of the rows in an unknown format whose report was made, so that each is reported once; a report that
    // failed or was cancelled is made again at the next read. [reports] makes one report at a time; a write and
    // [deleteAll] change the set without it, so that no lock of the logs' is taken under the key's guard.
    private val reports = Mutex()
    private val reportedUnknown = ConcurrentHashMap.newKeySet<String>()

    /** The open conversation, or null: what plan 7B's store reads first, so that a restore waits for one decryption. */
    suspend fun open(): StoredConversation? {
        val read = withContext(dispatcher) { dao.open()?.let(::stored) }
        report(listOfNotNull(read))
        return read
    }

    /** The index's shards, none when there is no index, each decrypted right after the query, on the same thread. */
    suspend fun shards(): List<StoredShard> =
        withContext(dispatcher) { dao.shards().map { row -> ensureActive(); shard(row) } }

    /** The stamp of the closed rows (how many, their newest last change and the sum of their last changes), in one query. */
    suspend fun stamp(): ConversationStamp = withContext(dispatcher) { dao.stamp() }

    /** The conversations [ids] that exist, in one query, each decrypted on the same thread: a search's matches, a transcript, a rebuild's page. */
    suspend fun read(ids: Collection<String>): List<StoredConversation> {
        val stored = withContext(dispatcher) { dao.rows(ids.toList()).map { row -> ensureActive(); stored(row) } }
        report(stored)
        return stored
    }

    /** The ids of the closed conversations, the oldest last change first, without their payloads: what a rebuild reads in pages. */
    suspend fun closedIds(): List<String> = withContext(dispatcher) { dao.closedIds() }

    /**
     * Stores [change] in one transaction: its conversations, each replacing its earlier row, its deletions, and its index.
     * Before an open conversation is stored, every other open one closes, so that at most one is open (design 6.1 of
     * sub-project 7). Every payload and shard is encrypted on one thread, right before the transaction.
     */
    suspend fun write(change: ConversationChange) {
        key.storing(dispatcher, dao::isEmpty) {
            val rows = change.conversations.map {
                val header = it.header
                ConversationRow(header.id, header.openedMillis, header.lastMillis, header.open, header.commands, sealing.encrypt(it.payload, associatedData(header.id)))
            }
            dao.write(rows, change.deleted.toList(), encrypted(change.index))
        }
        reportedUnknown.removeAll(change.conversations.map { it.header.id }.toSet())
        reportedUnknown.removeAll(change.deleted.toSet())
    }

    /**
     * Retention (design 9.3 of sub-project 7), in one transaction: deletes every closed conversation whose last change is
     * before [oldest] or after [newest], by the table's own times, and changes the index as [index] says; returns how many
     * conversations went.
     */
    suspend fun purge(oldest: Long, newest: Long, index: IndexUpdate): Int =
        key.storing(dispatcher, dao::isEmpty) { dao.purge(oldest, newest, encrypted(index)) }

    /**
     * "Delete history" (design 9.7 of sub-project 7), in one step on one thread: deletes every conversation, the open
     * one too, and the index, in one transaction that first runs [alsoDeleting] (PrivacyStore deletes both logs there),
     * and then erases the key, so that not even a copy of the database file, or what SQLite's journal still holds, can
     * be decrypted any more. It throws only when the transaction did not commit, which leaves every row, and what
     * [alsoDeleting] deleted, as it was. Once it has committed, the deletion is complete: it returns [KeyErasure.KEPT]
     * when the key could not be erased, which the next change erases before it stores anything, as it erases the key
     * that a process which ended between the two left. The next write makes a new key. Once it holds the key's guard,
     * the transaction and its result are one unit that cancellation cannot split ([ErasableKey.deleting]): a caller
     * cancelled meanwhile still gets the result, and its cancellation takes effect at its next suspension.
     */
    suspend fun deleteAll(alsoDeleting: () -> Unit = {}): KeyErasure =
        key.deleting(dispatcher) { dao.deleteAll(alsoDeleting) }.also { reportedUnknown.clear() }

    private fun encrypted(update: IndexUpdate): IndexRowUpdate = when (update) {
        IndexUpdate.Keep -> IndexRowUpdate.Keep
        is IndexUpdate.Update -> IndexRowUpdate.Update(update.put.map { (name, payload) -> shardRow(name, payload) }, update.deleted.toList())
        is IndexUpdate.Replace -> IndexRowUpdate.Replace(update.shards.map { (name, payload) -> shardRow(name, payload) })
    }

    private fun shardRow(name: String, payload: ByteArray) = ConversationIndexRow(name, sealing.encrypt(payload, indexData(name)))

    // Each conversation in an unknown format that has not been reported in this process yet, once its read has ended.
    private suspend fun report(conversations: List<StoredConversation>) {
        val unknown = conversations.filterIsInstance<StoredConversation.UnknownFormat>()
        if (unknown.isEmpty()) return
        reports.withLock {
            for (conversation in unknown) {
                if (conversation.header.id in reportedUnknown) continue
                onUnknownFormat()
                reportedUnknown += conversation.header.id
            }
        }
    }

    private fun stored(row: ConversationRow): StoredConversation {
        val header = ConversationHeader(row.id, row.openedMillis, row.lastMillis, row.open, row.commands)
        return try {
            StoredConversation.Readable(header, opened(row.payload, associatedData(row.id)))
        } catch (e: AEADBadTagException) {
            StoredConversation.FailedCheck(header)
        } catch (e: IllegalArgumentException) {
            StoredConversation.UnknownFormat(header)
        }
    }

    private fun shard(row: ConversationIndexRow): StoredShard = try {
        StoredShard.Readable(row.name, opened(row.payload, indexData(row.name)))
    } catch (e: AEADBadTagException) {
        StoredShard.Unreadable(row.name)
    } catch (e: IllegalArgumentException) {
        StoredShard.Unreadable(row.name)
    }

    // Decrypts with the key as it is, or fails the check when there is none, so that a read never makes the key.
    private fun opened(ciphertext: ByteArray, associatedData: ByteArray): ByteArray =
        key.withExisting { existing -> AesGcmFieldCipher { existing }.decrypt(ciphertext, associatedData) }

    private fun associatedData(id: String) = "conversations/$id".encodeToByteArray()

    // A shard's associated data, which no conversation's can be, because the prefixes differ.
    private fun indexData(name: String) = "conversation_index/$name".encodeToByteArray()
}
