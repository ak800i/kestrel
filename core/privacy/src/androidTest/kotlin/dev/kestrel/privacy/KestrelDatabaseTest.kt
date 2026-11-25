package dev.kestrel.privacy

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.privacy.db.ConversationRow
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


    @Test
    fun oldestAfterReturnsOneLogOldestFirstFromAPlace() = runBlocking {
        val dao = db.logDao()
        dao.insert(LogEntryRow(log = "activity", timestampMillis = 2, payload = byteArrayOf(2)))
        dao.insert(LogEntryRow(log = "activity", timestampMillis = 1, payload = byteArrayOf(1)))
        val sameTime = dao.insert(LogEntryRow(log = "activity", timestampMillis = 2, payload = byteArrayOf(3)))
        dao.insert(LogEntryRow(log = "network", timestampMillis = 1, payload = byteArrayOf(4)))

        val first = dao.oldestAfter("activity", Long.MIN_VALUE, Long.MIN_VALUE, 2)
        assertEquals(listOf(1, 2), first.map { it.payload.single().toInt() })
        val rest = dao.oldestAfter("activity", first.last().timestampMillis, first.last().id, 2)
        assertEquals(listOf(sameTime), rest.map { it.id })
    }

    // Design 6.1 of sub-project 7: at most one conversation is open, whatever the caller writes.
    @Test
    fun writingAnOpenConversationClosesTheOpenOne() = runBlocking {
        val dao = db.conversationDao()
        dao.write(listOf(conversation("a", last = 1, open = true)))
        dao.write(listOf(conversation("b", last = 2, open = true)))

        assertEquals(listOf("b" to true, "a" to false), dao.all().map { it.id to it.open })
    }

    // Design 9.6 of sub-project 7: a switch writes the closed and the opened conversation in one transaction.
    @Test
    fun oneWriteStoresEveryRowAndLeavesOnlyItsOpenRowOpen() = runBlocking {
        val dao = db.conversationDao()
        dao.write(listOf(conversation("a", last = 1, open = false), conversation("b", last = 2, open = true)))

        dao.write(listOf(conversation("b", last = 3, open = false), conversation("a", last = 3, open = true)))

        val rows = dao.all()
        assertEquals(setOf("a" to true, "b" to false), rows.map { it.id to it.open }.toSet())
        assertEquals(3L, rows.single { it.id == "b" }.lastMillis)
    }

    // Design 9.3 of sub-project 7: the open conversation is never purged, however old it is.
    @Test
    fun deleteOutsideKeepsTheOpenConversationAndBothLimits() = runBlocking {
        val dao = db.conversationDao()
        dao.write(
            listOf(
                conversation("before", last = 99, open = false),
                conversation("first", last = 100, open = false),
                conversation("last", last = 200, open = false),
                conversation("after", last = 201, open = false),
                conversation("open", last = 1, open = true),
            ),
        )

        assertEquals(2, dao.deleteOutside(oldest = 100, newest = 200))
        val rows = dao.all()
        assertEquals(listOf("last", "first", "open"), rows.map { it.id })
        assertEquals(listOf<Any>(199L, 200L, false, 2), rows.first().let { listOf(it.openedMillis, it.lastMillis, it.open, it.commands) })
    }

    private fun conversation(id: String, last: Long, open: Boolean) =
        ConversationRow(id, openedMillis = last - 1, lastMillis = last, open = open, commands = 2, payload = byteArrayOf(1))
}
