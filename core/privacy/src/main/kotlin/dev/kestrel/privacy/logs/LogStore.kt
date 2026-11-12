package dev.kestrel.privacy.logs

import dev.kestrel.privacy.crypto.FieldCipher
import dev.kestrel.privacy.db.LogDao
import dev.kestrel.privacy.db.LogEntryRow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

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

    /** Deletes the entries of both logs that are older than 7 days. */
    suspend fun purgeExpired(): Int {
        val now = clock()
        lastPurgeMillis = now
        return dao.deleteOlderThan(now - RETENTION_MILLIS)
    }

    /** "Delete history" wipes both logs (design spec, section 13.5). */
    suspend fun deleteHistory(): Int = dao.deleteAll()

    private suspend fun <T> append(log: String, serializer: KSerializer<T>, event: T) {
        val now = clock()
        val lastPurge = lastPurgeMillis
        if (lastPurge == null || now - lastPurge >= PURGE_INTERVAL_MILLIS) purgeExpired()
        val plaintext = json.encodeToString(serializer, event).encodeToByteArray()
        dao.insert(LogEntryRow(log = log, timestampMillis = now, payload = cipher.encrypt(plaintext, associatedData(log))))
    }

    private suspend fun <T> read(log: String, serializer: KSerializer<T>, limit: Int): List<LogEntry<T>> {
        purgeExpired()
        return dao.newest(log, limit).map { row ->
            val plaintext = cipher.decrypt(row.payload, associatedData(log)).decodeToString()
            LogEntry(row.id, row.timestampMillis, json.decodeFromString(serializer, plaintext))
        }
    }

    private fun associatedData(log: String) = "log_entries/$log".encodeToByteArray()

    companion object {
        const val RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000
        const val PURGE_INTERVAL_MILLIS = 60L * 60 * 1000
        const val DEFAULT_LIMIT = 500
        private const val ACTIVITY = "activity"
        private const val NETWORK = "network"
    }
}
