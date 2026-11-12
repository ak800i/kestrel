package dev.kestrel.privacy.logs

import dev.kestrel.privacy.crypto.FieldCipher
import dev.kestrel.privacy.db.LogDao
import dev.kestrel.privacy.db.LogEntryRow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import javax.crypto.AEADBadTagException

data class LogEntry<T>(val id: Long, val timestampMillis: Long, val event: T)

/** The encrypted activity log and network log, which keep entries for 7 days (design spec, section 13.5). */
class LogStore(
    private val dao: LogDao,
    private val cipher: FieldCipher,
    private val clock: () -> Long,
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var lastPurgeMillis: Long? = null

    suspend fun append(event: ActivityEvent) = append(ACTIVITY, ActivityEvent.serializer(), event)

    suspend fun append(event: NetworkEvent) = append(NETWORK, NetworkEvent.serializer(), event)

    suspend fun activity(limit: Int = DEFAULT_LIMIT): List<LogEntry<ActivityEvent>> =
        read(ACTIVITY, ActivityEvent.serializer(), limit)

    suspend fun network(limit: Int = DEFAULT_LIMIT): List<LogEntry<NetworkEvent>> =
        read(NETWORK, NetworkEvent.serializer(), limit)

    /**
     * Deletes the entries of both logs that are older than 7 days. It also deletes entries dated more than a
     * day ahead, which were written while the clock was wrong and would otherwise outlive the 7 days.
     */
    suspend fun purgeExpired(): Int {
        val now = clock()
        lastPurgeMillis = now
        return dao.deleteOutside(oldestMillis = now - RETENTION_MILLIS, newestMillis = now + FUTURE_TOLERANCE_MILLIS)
    }

    /** "Delete history" wipes both logs (design spec, section 13.5). */
    suspend fun deleteHistory(): Int = dao.deleteAll()

    private suspend fun <T> append(log: String, serializer: KSerializer<T>, event: T) {
        val now = clock()
        val lastPurge = lastPurgeMillis
        // After the clock went back, the interval check alone would wait until the clock caught up again.
        if (lastPurge == null || now < lastPurge || now - lastPurge >= PURGE_INTERVAL_MILLIS) purgeExpired()
        val plaintext = json.encodeToString(serializer, event).encodeToByteArray()
        dao.insert(LogEntryRow(log = log, timestampMillis = now, payload = cipher.encrypt(plaintext, associatedData(log))))
    }

    /**
     * Returns the newest entries. An entry that can never be read is deleted, so that it cannot block the
     * whole log, and the activity log says how many were deleted.
     */
    private suspend fun <T> read(log: String, serializer: KSerializer<T>, limit: Int): List<LogEntry<T>> {
        purgeExpired()
        val entries = mutableListOf<LogEntry<T>>()
        var unreadable = 0
        for (row in dao.newest(log, limit)) {
            val event = decodeOrNull(log, serializer, row)
            if (event == null) {
                dao.delete(row.id)
                unreadable++
            } else {
                entries += LogEntry(row.id, row.timestampMillis, event)
            }
        }
        if (unreadable > 0) {
            append(ACTIVITY, ActivityEvent.serializer(), ActivityEvent(ActivityKind.ERROR, "Deleted $unreadable unreadable $log log entries"))
        }
        return entries
    }

    /** Returns null for a payload that is damaged, was encrypted for another place or with a lost key, or has an old format. */
    private fun <T> decodeOrNull(log: String, serializer: KSerializer<T>, row: LogEntryRow): T? =
        try {
            json.decodeFromString(serializer, cipher.decrypt(row.payload, associatedData(log)).decodeToString())
        } catch (e: AEADBadTagException) {
            null
        } catch (e: IllegalArgumentException) {
            // Also covers kotlinx.serialization's SerializationException, which extends IllegalArgumentException.
            null
        }

    private fun associatedData(log: String) = "log_entries/$log".encodeToByteArray()

    companion object {
        const val RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000

        /** An entry dated further ahead than this was written while the clock was wrong. */
        const val FUTURE_TOLERANCE_MILLIS = 24L * 60 * 60 * 1000

        const val PURGE_INTERVAL_MILLIS = 60L * 60 * 1000
        const val DEFAULT_LIMIT = 500
        private const val ACTIVITY = "activity"
        private const val NETWORK = "network"
    }
}
