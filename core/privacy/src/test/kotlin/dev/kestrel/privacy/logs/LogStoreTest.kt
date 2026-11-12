package dev.kestrel.privacy.logs

import dev.kestrel.privacy.crypto.AesGcmFieldCipher
import dev.kestrel.privacy.db.LogDao
import dev.kestrel.privacy.db.LogEntryRow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import javax.crypto.KeyGenerator

class LogStoreTest {
    private val dao = FakeLogDao()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private var now = 1_000_000_000_000L
    private val store = LogStore(dao, AesGcmFieldCipher { key }) { now }

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
    fun deleteHistoryWipesBothLogs() = runTest {
        store.append(ActivityEvent(ActivityKind.NOTE, "note"))
        store.append(networkEvent("api.example.com"))

        store.deleteHistory()

        assertEquals(0, dao.rows.size)
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

    override suspend fun insert(row: LogEntryRow): Long {
        val id = nextId++
        rows += LogEntryRow(id, row.log, row.timestampMillis, row.payload)
        return id
    }

    override suspend fun newest(log: String, limit: Int): List<LogEntryRow> =
        rows.filter { it.log == log }
            .sortedWith(compareByDescending<LogEntryRow> { it.timestampMillis }.thenByDescending { it.id })
            .take(limit)

    override suspend fun deleteOlderThan(cutoffMillis: Long): Int {
        val expired = rows.filter { it.timestampMillis < cutoffMillis }
        rows.removeAll(expired)
        return expired.size
    }

    override suspend fun deleteAll(): Int {
        val count = rows.size
        rows.clear()
        return count
    }
}
