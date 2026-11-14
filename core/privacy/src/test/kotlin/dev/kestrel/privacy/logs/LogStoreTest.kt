package dev.kestrel.privacy.logs

import dev.kestrel.privacy.crypto.AesGcmFieldCipher
import dev.kestrel.privacy.db.LogDao
import dev.kestrel.privacy.db.LogEntryRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.crypto.KeyGenerator

class LogStoreTest {
    private val dao = FakeLogDao()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private var now = 1_000_000_000_000L
    private val store = LogStore(dao, AesGcmFieldCipher { key }, clock = { now }, dispatcher = Dispatchers.Unconfined)

    @Test
    fun readsActivityBackNewestFirst() = runTest {
        store.append(ActivityEvent(ActivityKind.NOTE, "first"))
        now += 1_000
        store.append(ActivityEvent(ActivityKind.ACTION, "second", taskId = "task-1"))

        val entries = store.activity()

        assertEquals(listOf("second", "first"), entries.map { it.event.text })
        assertEquals(ActivityEvent(ActivityKind.ACTION, "second", taskId = "task-1"), entries.first().event)
        assertEquals(now, entries.first().timestampMillis)
    }

    @Test
    fun storesPayloadsEncrypted() = runTest {
        store.append(ActivityEvent(ActivityKind.NOTE, "a secret note"))

        val stored = dao.rows.single().payload.decodeToString(throwOnInvalidSequence = false)

        assertFalse(stored.contains("secret"))
    }

    @Test
    fun keepsTheTwoLogsApart() = runTest {
        store.append(ActivityEvent(ActivityKind.NOTE, "note"))
        store.append(networkEvent("api.example.com"))

        assertEquals(listOf("note"), store.activity().map { it.event.text })
        assertEquals(listOf("api.example.com"), store.network().map { it.event.host })
    }

    @Test
    fun keepsEntriesForSevenDays() = runTest {
        store.append(ActivityEvent(ActivityKind.NOTE, "old"))
        now += LogStore.RETENTION_MILLIS

        assertEquals(listOf("old"), store.activity().map { it.event.text })

        now += 1
        assertEquals(emptyList<String>(), store.activity().map { it.event.text })
    }

    @Test
    fun appendingRemovesExpiredEntries() = runTest {
        store.append(ActivityEvent(ActivityKind.NOTE, "old"))
        now += LogStore.RETENTION_MILLIS + 1

        store.append(ActivityEvent(ActivityKind.NOTE, "new"))

        assertEquals(1, dao.rows.size)
    }

    @Test
    fun keepsEntriesUpToOneDayInTheFuture() = runTest {
        store.append(ActivityEvent(ActivityKind.NOTE, "ahead"))
        now -= LogStore.FUTURE_TOLERANCE_MILLIS

        assertEquals(listOf("ahead"), store.activity().map { it.event.text })

        now -= 1
        assertEquals(emptyList<String>(), store.activity().map { it.event.text })
    }

    @Test
    fun appendingAfterTheClockWentBackRemovesEntriesFromTheFuture() = runTest {
        store.append(ActivityEvent(ActivityKind.NOTE, "written while the clock was two days ahead"))
        now -= 2 * 24 * 60 * 60 * 1000L

        store.append(ActivityEvent(ActivityKind.NOTE, "new"))

        assertEquals(listOf(now), dao.rows.map { it.timestampMillis })
    }

    @Test
    fun deleteHistoryWipesBothLogs() = runTest {
        store.append(ActivityEvent(ActivityKind.NOTE, "note"))
        store.append(networkEvent("api.example.com"))

        store.deleteHistory()

        assertEquals(0, dao.rows.size)
    }

    @Test
    fun runsTheCipherOnTheStoreDispatcher() = runTest {
        val threads = CopyOnWriteArrayList<Thread>()
        val cipher = AesGcmFieldCipher { key.also { threads += Thread.currentThread() } }
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val store = LogStore(dao, cipher, clock = { now }, dispatcher = dispatcher)
            store.append(ActivityEvent(ActivityKind.NOTE, "note"))
            store.activity()

            assertEquals(setOf(withContext(dispatcher) { Thread.currentThread() }), threads.toSet())
        }
    }

    @Test
    fun deletesEntriesThatCanNeverBeReadAndSaysSo() = runTest {
        store.append(ActivityEvent(ActivityKind.NOTE, "good"))
        val cipher = AesGcmFieldCipher { key }
        val activityPlace = "log_entries/activity".encodeToByteArray()
        val networkPlace = "log_entries/network".encodeToByteArray()
        val damaged = byteArrayOf(1, 2, 3)
        val oldFormat = cipher.encrypt("""{"text":"written before 'kind' existed"}""".encodeToByteArray(), activityPlace)
        val movedFromNetworkLog = cipher.encrypt("""{"kind":"NOTE","text":"moved"}""".encodeToByteArray(), networkPlace)
        listOf(damaged, oldFormat, movedFromNetworkLog).forEachIndexed { index, payload ->
            dao.rows += LogEntryRow(id = 100L + index, log = "activity", timestampMillis = now, payload = payload)
        }

        assertEquals(listOf("good"), store.activity().map { it.event.text })
        assertEquals(emptyList<Long>(), dao.rows.map { it.id }.filter { it in 100L..102L })
        assertEquals(listOf("Deleted 3 unreadable activity log entries", "good"), store.activity().map { it.event.text })
    }

    @Test
    fun keepPurgingPurgesOnceADayByTheWallClock() = runTest {
        val job = backgroundScope.launch { store.keepPurging { throw it } }
        runCurrent()
        assertEquals(1, dao.purges)

        // The phone slept: an hour of the coroutine's time, but a whole day on the wall clock.
        now += LogStore.DAILY_PURGE_MILLIS
        advanceTimeBy(LogStore.PURGE_CHECK_MILLIS)
        runCurrent()
        assertEquals(2, dao.purges)

        // A day of hourly checks without a day passing on the wall clock purges nothing more.
        repeat(23) {
            now += LogStore.PURGE_CHECK_MILLIS / 2
            advanceTimeBy(LogStore.PURGE_CHECK_MILLIS)
            runCurrent()
        }
        assertEquals(2, dao.purges)
        job.cancel()
    }

    @Test
    fun keepPurgingTriesAgainAfterAFailure() = runTest {
        dao.failNextPurge = true
        val failures = mutableListOf<Throwable>()
        val job = backgroundScope.launch { store.keepPurging { failures += it } }
        runCurrent()
        assertEquals(listOf("the disk is full"), failures.map { it.message })

        advanceTimeBy(LogStore.PURGE_CHECK_MILLIS)
        runCurrent()

        assertEquals(2, dao.purges)
        assertEquals(1, failures.size)
        job.cancel()
    }

    private fun networkEvent(host: String) = NetworkEvent(
        method = "GET",
        host = host,
        path = "/",
        requestBytes = 0,
        responseBytes = 10,
        status = 200,
        outcome = NetworkOutcome.COMPLETED,
    )
}

/** Mimics the SQL in [LogDao]; KestrelDatabaseTest checks the real queries on a device. */
private class FakeLogDao : LogDao {
    val rows = mutableListOf<LogEntryRow>()
    private var nextId = 1L
    var purges = 0
    var failNextPurge = false

    override suspend fun insert(row: LogEntryRow): Long {
        val id = nextId++
        rows += LogEntryRow(id, row.log, row.timestampMillis, row.payload)
        return id
    }

    override suspend fun newest(log: String, limit: Int): List<LogEntryRow> =
        rows.filter { it.log == log }
            .sortedWith(compareByDescending<LogEntryRow> { it.timestampMillis }.thenByDescending { it.id })
            .take(limit)

    override suspend fun deleteOutside(oldestMillis: Long, newestMillis: Long): Int {
        purges++
        if (failNextPurge) {
            failNextPurge = false
            throw IllegalStateException("the disk is full")
        }
        val outside = rows.filter { it.timestampMillis < oldestMillis || it.timestampMillis > newestMillis }
        rows.removeAll(outside)
        return outside.size
    }

    override suspend fun delete(id: Long): Int = if (rows.removeIf { it.id == id }) 1 else 0

    override suspend fun deleteAll(): Int {
        val count = rows.size
        rows.clear()
        return count
    }
}
