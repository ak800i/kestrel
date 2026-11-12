package dev.kestrel.privacy

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.privacy.db.KestrelDatabase
import dev.kestrel.privacy.db.LogEntryRow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KestrelDatabaseTest {
    private lateinit var db: KestrelDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, KestrelDatabase::class.java).build()
    }

    @After
    fun close() {
        db.close()
    }

    @Test
    fun newestReturnsOneLogNewestFirst() = runBlocking {
        val dao = db.logDao()
        dao.insert(LogEntryRow(log = "activity", timestampMillis = 1, payload = byteArrayOf(1)))
        dao.insert(LogEntryRow(log = "activity", timestampMillis = 3, payload = byteArrayOf(3)))
        dao.insert(LogEntryRow(log = "network", timestampMillis = 2, payload = byteArrayOf(2)))

        assertEquals(listOf(3L, 1L), dao.newest("activity", 10).map { it.timestampMillis })
        assertEquals(listOf(3L), dao.newest("activity", 1).map { it.timestampMillis })
    }

    @Test
    fun deleteOlderThanKeepsEntriesAtTheCutoff() = runBlocking {
        val dao = db.logDao()
        dao.insert(LogEntryRow(log = "activity", timestampMillis = 99, payload = byteArrayOf(1)))
        dao.insert(LogEntryRow(log = "network", timestampMillis = 100, payload = byteArrayOf(2)))

        assertEquals(1, dao.deleteOlderThan(100))
        assertEquals(listOf(100L), dao.newest("network", 10).map { it.timestampMillis })
    }
}
