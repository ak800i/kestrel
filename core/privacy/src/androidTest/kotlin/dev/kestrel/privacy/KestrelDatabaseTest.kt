package dev.kestrel.privacy

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.privacy.db.KestrelDatabase
import dev.kestrel.privacy.db.LogEntryRow
import dev.kestrel.privacy.db.SettingRow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun deleteOutsideKeepsEntriesAtBothLimits() = runBlocking {
        val dao = db.logDao()
        dao.insert(LogEntryRow(log = "activity", timestampMillis = 99, payload = byteArrayOf(1)))
        dao.insert(LogEntryRow(log = "network", timestampMillis = 100, payload = byteArrayOf(2)))
        dao.insert(LogEntryRow(log = "network", timestampMillis = 200, payload = byteArrayOf(3)))
        dao.insert(LogEntryRow(log = "activity", timestampMillis = 201, payload = byteArrayOf(4)))

        assertEquals(2, dao.deleteOutside(oldestMillis = 100, newestMillis = 200))
        assertEquals(listOf(200L, 100L), dao.newest("network", 10).map { it.timestampMillis })
        assertEquals(emptyList<Long>(), dao.newest("activity", 10).map { it.timestampMillis })
    }

    @Test
    fun settingsUpsertReplacesTheValue() = runBlocking {
        val dao = db.settingsDao()
        assertNull(dao.observe("volume").first())

        dao.put(SettingRow("volume", byteArrayOf(1)))
        dao.put(SettingRow("volume", byteArrayOf(2)))

        assertArrayEquals(byteArrayOf(2), dao.get("volume")!!.payload)
        assertArrayEquals(byteArrayOf(2), dao.observe("volume").first()!!.payload)
    }
}
