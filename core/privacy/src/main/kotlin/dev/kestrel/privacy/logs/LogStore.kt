package dev.kestrel.privacy.logs

import dev.kestrel.privacy.crypto.FieldCipher
import dev.kestrel.privacy.db.LogDao
import dev.kestrel.privacy.db.LogEntryRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.Writer
import java.time.Instant
import javax.crypto.AEADBadTagException

data class LogEntry<T>(val id: Long, val timestampMillis: Long, val event: T)

/**
 * The encrypted activity log and network log, which keep entries for 7 days (design spec, section 13.5). Its functions
 * are safe to call from the main thread, because they encrypt, decrypt and query on [dispatcher].
 *
 * Every append and every deletion of the history take their turns under [order], in the order in which they asked
 * (design 9.7 of sub-project 7): an entry whose append asked before "delete history" is deleted with the history, and
 * one that asked after it stays. [order] is the database's ([dev.kestrel.privacy.db.KestrelDatabase.historyOrder]), so
 * every store of the logs on it shares it.
 */
class LogStore(
    private val dao: LogDao,
    private val cipher: FieldCipher,
    private val clock: () -> Long,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val order: Mutex = Mutex(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var lastPurgeMillis: Long? = null

    suspend fun append(event: ActivityEvent) =
        order.withLock { withContext(dispatcher) { append(ACTIVITY, ActivityEvent.serializer(), event) } }

    suspend fun append(event: NetworkEvent) =
        order.withLock { withContext(dispatcher) { append(NETWORK, NetworkEvent.serializer(), event) } }

    /**
     * Appends [event] in [scope], for a caller that cannot wait: it takes its turn under [order] before this returns,
     * so that it is written in the order of the calls, before a deletion of the history that is asked for later.
     */
    fun post(event: ActivityEvent, scope: CoroutineScope): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) { append(event) }

    /**
     * Runs [block] in its turn under [order], as "delete history" does (design 9.7 of sub-project 7): after every
     * append that asked before it, and before every append that asks while it runs.
     */
    suspend fun <T> inOrder(block: suspend () -> T): T = order.withLock { block() }

    suspend fun activity(limit: Int = DEFAULT_LIMIT): List<LogEntry<ActivityEvent>> =
        withContext(dispatcher) { read(ACTIVITY, ActivityEvent.serializer(), limit) }

    suspend fun network(limit: Int = DEFAULT_LIMIT): List<LogEntry<NetworkEvent>> =
        withContext(dispatcher) { read(NETWORK, NetworkEvent.serializer(), limit) }

    /**
     * Deletes the entries of both logs that are older than 7 days. It also deletes entries dated more than a
     * day ahead, which were written while the clock was wrong and would otherwise outlive the 7 days.
     */
    suspend fun purgeExpired(): Int = withContext(dispatcher) {
        val now = clock()
        lastPurgeMillis = now
        dao.deleteOutside(oldestMillis = now - RETENTION_MILLIS, newestMillis = now + FUTURE_TOLERANCE_MILLIS)
    }

    /**
     * Purges the logs once a day for as long as it runs, for a service that runs for days (design spec, section 13.5).
     * It checks every hour against [clock], because a coroutine's delay stops counting while the phone sleeps. A
     * failed purge goes to [onFailure], and the next check tries again.
     */
    suspend fun keepPurging(onFailure: (Throwable) -> Unit): Nothing {
        var lastPurge: Long? = null
        while (true) {
            val now = clock()
            val last = lastPurge
            // After the clock went back, the interval check alone would wait until the clock caught up again.
            if (last == null || now < last || now - last >= DAILY_PURGE_MILLIS) {
                try {
                    purgeExpired()
                    lastPurge = now
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onFailure(e)
                }
            }
            delay(PURGE_CHECK_MILLIS)
        }
    }

    /**
     * Writes both logs to [output] as a [LogExport] in JSON, with every entry, not only the newest. The logs leave the
     * phone only this way, when the owner exports them to a file of their choice (design spec, section 13.5). It reads and
     * writes [EXPORT_PAGE] entries at a time, so that a log of tens of megabytes never has to fit into memory (design D32
     * item 47 of sub-project 5). An entry that can never be read is left out and deleted, as when the log is read. It
     * flushes [output] and leaves it open. When the export fails, it truncates a file's stream ([FileOutputStream], which
     * a document provider's file gives) to nothing before it throws, so that no cut-off JSON stays in the file; any other
     * stream keeps what was written.
     */
    suspend fun export(output: OutputStream) = withContext(dispatcher) {
        purgeExpired()
        val writer = output.bufferedWriter(Charsets.UTF_8)
        val unreadable = try {
            writer.write("{\"exportedAt\":")
            writer.write(json.encodeToString(String.serializer(), isoTime(clock())))
            writer.write(",\"activity\":[")
            val activity = exportLog(writer, ACTIVITY, ActivityEvent.serializer())
            writer.write("],\"network\":[")
            val network = exportLog(writer, NETWORK, NetworkEvent.serializer())
            writer.write("]}")
            writer.flush()
            activity to network
        } catch (e: Throwable) {
            emptied(output, e)
            throw e
        }
        noteUnreadable(ACTIVITY, unreadable.first)
        noteUnreadable(NETWORK, unreadable.second)
    }

    private fun emptied(output: OutputStream, failure: Throwable) {
        val file = output as? FileOutputStream ?: return
        try {
            file.channel.truncate(0)
        } catch (e: IOException) {
            failure.addSuppressed(e)
        }
    }

    /** Writes the entries of [log], oldest first and separated by commas, page by page; returns how many were unreadable. */
    private suspend fun <T> exportLog(writer: Writer, log: String, serializer: KSerializer<T>): Int {
        val entry = LogExport.Entry.serializer(serializer)
        var afterMillis = Long.MIN_VALUE
        var afterId = Long.MIN_VALUE
        var first = true
        var unreadable = 0
        while (true) {
            val rows = dao.oldestAfter(log, afterMillis, afterId, EXPORT_PAGE)
            for (row in rows) {
                val event = decodeOrNull(log, serializer, row)
                if (event == null) {
                    dao.delete(row.id)
                    unreadable++
                    continue
                }
                if (!first) writer.write(",")
                first = false
                writer.write(json.encodeToString(entry, LogExport.Entry(isoTime(row.timestampMillis), event)))
            }
            if (rows.size < EXPORT_PAGE) return unreadable
            afterMillis = rows.last().timestampMillis
            afterId = rows.last().id
        }
    }

    private fun isoTime(millis: Long) = Instant.ofEpochMilli(millis).toString()

    /** "Delete history" wipes both logs (design spec, section 13.5), in its turn under [order]. */
    suspend fun deleteHistory(): Int = order.withLock { withContext(dispatcher) { dao.deleteAll() } }

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
        noteUnreadable(log, unreadable)
        return entries
    }

    /** The activity log says how many unreadable entries of [log] were deleted. */
    private suspend fun noteUnreadable(log: String, unreadable: Int) {
        if (unreadable > 0) {
            order.withLock { append(ACTIVITY, ActivityEvent.serializer(), ActivityEvent(ActivityKind.ERROR, "Deleted $unreadable unreadable $log log entries")) }
        }
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
        const val DAILY_PURGE_MILLIS = 24L * 60 * 60 * 1000
        const val PURGE_CHECK_MILLIS = 60L * 60 * 1000
        const val DEFAULT_LIMIT = 500

        /** How many entries the export reads and writes at a time (design D32 item 47 of sub-project 5). */
        const val EXPORT_PAGE = 100
        private const val ACTIVITY = "activity"
        private const val NETWORK = "network"
    }
}
