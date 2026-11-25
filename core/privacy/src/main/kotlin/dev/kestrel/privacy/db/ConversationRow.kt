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

/**
 * The table of conversations. It keeps at most one conversation open (design 6.1 of sub-project 7): [write] closes the
 * other open rows in the same transaction as it stores an open one, and nothing else opens a row.
 */
@Dao
abstract class ConversationDao {
    /**
     * Inserts or replaces [rows], all in one transaction, so that a switch to another conversation (design 9.6 of
     * sub-project 7) is never stored halfway. Before an open row is stored, every other open row closes.
     */
    @Transaction
    open suspend fun write(rows: List<ConversationRow>) {
        for (row in rows) {
            if (row.open) closeOthers(row.id)
            upsert(row)
        }
    }

    /**
     * Every conversation's row, the newest last change first, in one query: the first read of the archive (design 4.3 and
     * R3 of sub-project 7), which one query per row would make many times slower.
     */
    @Query("SELECT * FROM conversations ORDER BY last_millis DESC")
    abstract suspend fun all(): List<ConversationRow>

    @Query("DELETE FROM conversations WHERE id = :id")
    abstract suspend fun delete(id: String): Int

    /** Deletes the closed conversations whose last change is before [oldest] or after [newest]; the open one stays. */
    @Query("DELETE FROM conversations WHERE open = 0 AND (last_millis < :oldest OR last_millis > :newest)")
    abstract suspend fun deleteOutside(oldest: Long, newest: Long): Int

    @Query("DELETE FROM conversations")
    abstract suspend fun deleteAll(): Int

    @Upsert
    protected abstract suspend fun upsert(row: ConversationRow)

    @Query("UPDATE conversations SET open = 0 WHERE open = 1 AND id != :id")
    protected abstract suspend fun closeOthers(id: String): Int
}
