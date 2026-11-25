package dev.kestrel.privacy.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/**
 * One past or open conversation (design 9.1 and 9.2 of sub-project 7). Only the payload is sensitive, and it is encrypted
 * under the key kestrel.conversations and bound to the id. The other columns are plain, for sorting and retention.
 */
@Entity(tableName = "conversations", indices = [Index(value = ["last_millis"])])
class ConversationRow(
    /** The conversation's random 128-bit id, as text. */
    @PrimaryKey val id: String,
    @ColumnInfo(name = "opened_millis") val openedMillis: Long,
    /** The wall-clock time of the conversation's last change, which retention counts from. */
    @ColumnInfo(name = "last_millis") val lastMillis: Long,
    val open: Boolean,
    /** How many commands the conversation holds. */
    val commands: Int,
    val payload: ByteArray,
)

/**
 * A shard of the index of past conversations (design 9.2 of sub-project 7): one row per shard, named by the shard's number,
 * whose payload plan 7B's store encodes and the archive encrypts under the key kestrel.conversations, bound to its name. The
 * shards have a table of their own, so that every query of the conversations' table reads conversations only.
 */
@Entity(tableName = "conversation_index")
class ConversationIndexRow(@PrimaryKey val name: String, val payload: ByteArray)

/**
 * A conversation's row as a query of rows gives it: its plain fields, its payload's size, and the payload itself when
 * it is at most [ConversationDao.PIECE_BYTES]; [ConversationDao.whole] reads a larger one in pieces.
 */
class ConversationRowRead(
    val id: String,
    @ColumnInfo(name = "opened_millis") val openedMillis: Long,
    @ColumnInfo(name = "last_millis") val lastMillis: Long,
    val open: Boolean,
    val commands: Int,
    val size: Int,
    val payload: ByteArray?,
)

/**
 * A shard's row as a query of shards gives it: its name, its payload's size, and the payload when it is at most a
 * piece.
 */
class IndexRowRead(val name: String, val size: Int, val payload: ByteArray?)

/**
 * What the table holds of closed rows: how many, their newest last change and the sum of their last changes, 0 without
 * rows.
 */
data class ConversationStamp(val closed: Int, val newest: Long, val total: Long)

/** What a write does to the shards' rows: keeps them; puts [Update.put] and deletes [Update.deleted]; or replaces them all with [Replace.rows]. */
sealed interface IndexRowUpdate {
    data object Keep : IndexRowUpdate

    class Update(val put: List<ConversationIndexRow>, val deleted: List<String>) : IndexRowUpdate

    class Replace(val rows: List<ConversationIndexRow>) : IndexRowUpdate
}

/**
 * The tables of conversations and of their index. Its functions block: `ConversationArchiveStore` calls them on its own
 * dispatcher, so that a query and the decryption of its rows run on one thread, with no hop between them (design R3 of
 * sub-project 7). It keeps at most one conversation open (design 6.1): [write] closes the other open rows in the same
 * transaction as it stores an open one, and nothing else opens a row.
 *
 * Android's cursor window holds at most 2 MB, and a query fails on a row that does not fit into it, while a write has
 * no such bound. So the queries of rows give a payload at most a piece ([PIECE_BYTES]) long, and [whole] reads a larger
 * one in pieces, each well within the window, in one transaction, so that a conversation or a shard of any size reads
 * back.
 */
@Dao
abstract class ConversationDao {
    /** The open conversation's row, or null: what a restore reads. */
    @Query("SELECT id, opened_millis, last_millis, open, commands, length(payload) AS size, CASE WHEN length(payload) <= $PIECE_BYTES THEN payload END AS payload FROM conversations WHERE open = 1 LIMIT 1")
    abstract fun open(): ConversationRowRead?

    /** Every shard's row of the index. */
    @Query("SELECT name, length(payload) AS size, CASE WHEN length(payload) <= $PIECE_BYTES THEN payload END AS payload FROM conversation_index")
    abstract fun shards(): List<IndexRowRead>

    /** What the table holds of closed rows: how many, their newest last change and the sum of their last changes. */
    @Query("SELECT COUNT(*) AS closed, COALESCE(MAX(last_millis), 0) AS newest, COALESCE(SUM(last_millis), 0) AS total FROM conversations WHERE open = 0")
    abstract fun stamp(): ConversationStamp

    /**
     * The conversations [ids] that exist, in one query (design 9.2 of sub-project 7): a search's matches, a transcript,
     * a rebuild's page.
     */
    @Query("SELECT id, opened_millis, last_millis, open, commands, length(payload) AS size, CASE WHEN length(payload) <= $PIECE_BYTES THEN payload END AS payload FROM conversations WHERE id IN (:ids)")
    abstract fun rows(ids: List<String>): List<ConversationRowRead>

    /**
     * The whole row that [read] gives, its payload read in pieces when it is larger than a piece; null when the row
     * went.
     */
    fun whole(read: ConversationRowRead): ConversationRow? =
        read.payload?.let { ConversationRow(read.id, read.openedMillis, read.lastMillis, read.open, read.commands, it) } ?: wholeConversation(read.id)

    /**
     * The whole shard that [read] gives, its payload read in pieces when it is larger than a piece; null when the shard
     * went.
     */
    fun whole(read: IndexRowRead): ConversationIndexRow? = read.payload?.let { ConversationIndexRow(read.name, it) } ?: wholeShard(read.name)

    /**
     * The row [id] with its payload read in pieces, in one transaction, so that the pieces belong to one version of the
     * row.
     */
    @Transaction
    open fun wholeConversation(id: String): ConversationRow? {
        val head = conversationHead(id) ?: return null
        val payload = pieces(head.size) { from, count -> conversationPiece(id, from, count) } ?: return null
        return ConversationRow(head.id, head.openedMillis, head.lastMillis, head.open, head.commands, payload)
    }

    /** The shard [name] with its payload read in pieces, in one transaction. */
    @Transaction
    open fun wholeShard(name: String): ConversationIndexRow? {
        val size = shardSize(name) ?: return null
        return pieces(size) { from, count -> shardPiece(name, from, count) }?.let { ConversationIndexRow(name, it) }
    }

    /**
     * The ids of the closed rows, the oldest last change first, without their payloads: what a rebuild reads in pages.
     */
    @Query("SELECT id FROM conversations WHERE open = 0 ORDER BY last_millis ASC")
    abstract fun closedIds(): List<String>

    /**
     * Whether both tables are empty, as "delete history" leaves them (design 9.7 of sub-project 7): then nothing is stored
     * under the archive's key.
     */
    @Query("SELECT NOT EXISTS (SELECT 1 FROM conversations) AND NOT EXISTS (SELECT 1 FROM conversation_index)")
    abstract fun isEmpty(): Boolean

    /**
     * One transaction, so that a switch to another conversation (design 9.6 of sub-project 7) and the index that goes with
     * a change are never stored halfway: deletes [deleted], stores [rows], closing every other open row before an open one,
     * and changes the shards' rows as [index] says.
     */
    @Transaction
    open fun write(rows: List<ConversationRow>, deleted: List<String>, index: IndexRowUpdate) {
        for (id in deleted) delete(id)
        for (row in rows) {
            if (row.open) closeOthers(row.id)
            upsert(row)
        }
        change(index)
    }

    /**
     * Retention (design 9.3 of sub-project 7), in one transaction: deletes every closed row whose last change is before
     * [oldest] or after [newest], by the table's own times, and changes the shards' rows as [index] says; returns how many
     * rows went.
     */
    @Transaction
    open fun purge(oldest: Long, newest: Long, index: IndexRowUpdate): Int = deleteClosedOutside(oldest, newest).also { change(index) }

    /**
     * Deletes every conversation, the open one too, and the index, in one transaction, which first runs [alsoDeleting]:
     * what else "delete history" deletes in the same database (both logs, in PrivacyStore's), so that all of it goes,
     * or nothing (design 9.7 of sub-project 7).
     */
    @Transaction
    open fun deleteAll(alsoDeleting: () -> Unit) {
        alsoDeleting()
        deleteConversations()
        deleteIndexes()
    }

    // The [size] bytes of a payload, read [PIECE_BYTES] at a time from the 1-based offset that [piece] takes; null when
    // the row went.
    private inline fun pieces(size: Int, piece: (from: Int, count: Int) -> ByteArray?): ByteArray? {
        val payload = ByteArray(size)
        var at = 0
        while (at < size) {
            val next = piece(at + 1, minOf(PIECE_BYTES, size - at)) ?: return null
            check(next.isNotEmpty()) { "A payload ended before its size" }
            next.copyInto(payload, at)
            at += next.size
        }
        return payload
    }

    private fun change(index: IndexRowUpdate) {
        when (index) {
            IndexRowUpdate.Keep -> Unit
            is IndexRowUpdate.Update -> {
                for (name in index.deleted) deleteIndex(name)
                for (row in index.put) upsertIndex(row)
            }
            is IndexRowUpdate.Replace -> {
                deleteIndexes()
                for (row in index.rows) upsertIndex(row)
            }
        }
    }

    @Query("SELECT id, opened_millis, last_millis, open, commands, length(payload) AS size, NULL AS payload FROM conversations WHERE id = :id")
    protected abstract fun conversationHead(id: String): ConversationRowRead?

    @Query("SELECT substr(payload, :from, :count) FROM conversations WHERE id = :id")
    protected abstract fun conversationPiece(id: String, from: Int, count: Int): ByteArray?

    @Query("SELECT length(payload) FROM conversation_index WHERE name = :name")
    protected abstract fun shardSize(name: String): Int?

    @Query("SELECT substr(payload, :from, :count) FROM conversation_index WHERE name = :name")
    protected abstract fun shardPiece(name: String, from: Int, count: Int): ByteArray?

    @Query("DELETE FROM conversations WHERE id = :id")
    protected abstract fun delete(id: String): Int

    @Query("DELETE FROM conversations WHERE open = 0 AND (last_millis < :oldest OR last_millis > :newest)")
    protected abstract fun deleteClosedOutside(oldest: Long, newest: Long): Int

    @Upsert
    protected abstract fun upsert(row: ConversationRow)

    @Query("UPDATE conversations SET open = 0 WHERE open = 1 AND id != :id")
    protected abstract fun closeOthers(id: String): Int

    @Upsert
    protected abstract fun upsertIndex(row: ConversationIndexRow)

    @Query("DELETE FROM conversation_index WHERE name = :name")
    protected abstract fun deleteIndex(name: String): Int

    @Query("DELETE FROM conversations")
    protected abstract fun deleteConversations(): Int

    @Query("DELETE FROM conversation_index")
    protected abstract fun deleteIndexes(): Int

    companion object {
        /**
         * The largest payload that a query of rows reads at once, and the size of each piece of a larger one: a quarter
         * of Android's cursor window of 2 MB, so that a piece and the row's other columns always fit.
         */
        const val PIECE_BYTES = 512 * 1024
    }
}
