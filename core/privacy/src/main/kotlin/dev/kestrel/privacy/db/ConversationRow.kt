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
 * The plain fields of a conversation's row, without its payload, as the archive takes and gives them. Its [open] is the
 * one truth about whether the conversation is open (design 6.1 of sub-project 7): the conversation's store reads it from
 * here; the payload does not hold it.
 */
data class ConversationHeader(
    val id: String,
    val openedMillis: Long,
    val lastMillis: Long,
    val open: Boolean,
    val commands: Int,
)

/** What the table holds of closed rows: how many, their newest last change and the sum of their last changes, 0 without rows. */
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
 */
@Dao
abstract class ConversationDao {
    /** The open conversation's row, or null: what a restore reads. */
    @Query("SELECT * FROM conversations WHERE open = 1 LIMIT 1")
    abstract fun open(): ConversationRow?

    /** Every shard's row of the index. */
    @Query("SELECT * FROM conversation_index")
    abstract fun shards(): List<ConversationIndexRow>

    /** What the table holds of closed rows: how many, their newest last change and the sum of their last changes. */
    @Query("SELECT COUNT(*) AS closed, COALESCE(MAX(last_millis), 0) AS newest, COALESCE(SUM(last_millis), 0) AS total FROM conversations WHERE open = 0")
    abstract fun stamp(): ConversationStamp

    /** The conversations [ids] that exist, in one query (design 9.2 of sub-project 7): a search's matches, a transcript, a rebuild's page. */
    @Query("SELECT * FROM conversations WHERE id IN (:ids)")
    abstract fun rows(ids: List<String>): List<ConversationRow>

    /** The ids of the closed rows, the oldest last change first, without their payloads: what a rebuild reads in pages. */
    @Query("SELECT id FROM conversations WHERE open = 0 ORDER BY last_millis ASC")
    abstract fun closedIds(): List<String>

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

    /** Deletes every conversation, the open one too, and the index, in one transaction. */
    @Transaction
    open fun deleteAll() {
        deleteConversations()
        deleteIndexes()
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
}
