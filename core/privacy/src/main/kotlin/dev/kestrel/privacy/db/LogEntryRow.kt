package dev.kestrel.privacy.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/** One entry of the activity log or the network log. Only the payload is sensitive, and it is encrypted. */
@Entity(
    tableName = "log_entries",
    indices = [Index(value = ["log", "timestamp_millis"]), Index(value = ["timestamp_millis"])],
)
class LogEntryRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val log: String,
    @ColumnInfo(name = "timestamp_millis") val timestampMillis: Long,
    val payload: ByteArray,
)

@Dao
interface LogDao {
    @Insert
    suspend fun insert(row: LogEntryRow): Long

    @Query("SELECT * FROM log_entries WHERE log = :log ORDER BY timestamp_millis DESC, id DESC LIMIT :limit")
    suspend fun newest(log: String, limit: Int): List<LogEntryRow>

    /** Deletes the entries dated before [oldestMillis] or after [newestMillis]. */
    @Query("DELETE FROM log_entries WHERE timestamp_millis < :oldestMillis OR timestamp_millis > :newestMillis")
    suspend fun deleteOutside(oldestMillis: Long, newestMillis: Long): Int

    @Query("DELETE FROM log_entries WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query("DELETE FROM log_entries")
    suspend fun deleteAll(): Int
}
