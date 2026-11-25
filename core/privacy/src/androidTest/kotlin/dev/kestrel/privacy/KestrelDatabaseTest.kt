package dev.kestrel.privacy

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.privacy.db.ConversationIndexRow
import dev.kestrel.privacy.db.ConversationRow
import dev.kestrel.privacy.db.ConversationStamp
import dev.kestrel.privacy.db.IndexRowUpdate
import dev.kestrel.privacy.db.KestrelDatabase
import dev.kestrel.privacy.db.LogEntryRow
import dev.kestrel.privacy.db.SettingRow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun writingAnOpenConversationClosesTheOpenOne() {
        val dao = db.conversationDao()
        dao.write(listOf(conversation("a", last = 1, open = true)), emptyList(), IndexRowUpdate.Keep)
        dao.write(listOf(conversation("b", last = 2, open = true)), emptyList(), IndexRowUpdate.Keep)

        assertEquals(setOf("b" to true, "a" to false), dao.rows(listOf("a", "b")).map { it.id to it.open }.toSet())
        assertEquals("b", dao.open()!!.id)
    }

    // Design 9.2 and 9.6 of sub-project 7: a switch writes the closed and the opened conversation and the shards in one transaction.
    @Test
    fun oneWriteStoresItsRowsDeletesItsRowsAndChangesTheShards() {
        val dao = db.conversationDao()
        dao.write(listOf(conversation("a", last = 1, open = false), conversation("b", last = 2, open = true), conversation("c", last = 2, open = false)), emptyList(), IndexRowUpdate.Replace(listOf(shard("0", 5), shard("1", 6))))

        dao.write(
            listOf(conversation("b", last = 3, open = false), conversation("a", last = 3, open = true)),
            listOf("c"),
            IndexRowUpdate.Update(listOf(shard("1", 7), shard("2", 8)), listOf("0")),
        )

        val rows = dao.rows(listOf("a", "b", "c"))
        assertEquals(setOf("a" to true, "b" to false), rows.map { it.id to it.open }.toSet())
        assertEquals(3L, rows.single { it.id == "b" }.lastMillis)
        assertEquals(mapOf("1" to 7.toByte(), "2" to 8.toByte()), dao.shards().associate { it.name to it.payload.single() })
        dao.write(emptyList(), emptyList(), IndexRowUpdate.Replace(emptyList()))
        assertTrue(dao.shards().isEmpty())
    }

    // Design 9.2 and R3 of sub-project 7: a restore reads the open row alone; the shards and the closed rows' stamp apart.
    @Test
    fun theOpenRowTheShardsAndTheStampOfTheClosedRowsAreReadApart() {
        val dao = db.conversationDao()
        assertEquals(ConversationStamp(0, 0, 0), dao.stamp())
        dao.write(
            listOf(conversation("b", last = 300, open = false), conversation("a", last = 100, open = false), conversation("c", last = 500, open = true)),
            emptyList(),
            IndexRowUpdate.Update(listOf(shard("0", 9)), emptyList()),
        )

        assertArrayEquals(byteArrayOf(9), dao.shards().single().payload)
        assertEquals(ConversationStamp(closed = 2, newest = 300, total = 400), dao.stamp())
        assertEquals(listOf<Any>("c", 499L, 500L, true, 2), dao.open()!!.let { listOf(it.id, it.openedMillis, it.lastMillis, it.open, it.commands) })
        assertEquals(listOf("a", "b"), dao.closedIds())
    }

    // Design 9.3 of sub-project 7: retention deletes by the table's own times, never the open row, with the shards in one transaction.
    @Test
    fun purgeDeletesTheClosedRowsOutsideTheWindowAndChangesTheShards() {
        val dao = db.conversationDao()
        dao.write(
            listOf(conversation("old", last = 5, open = false), conversation("kept", last = 50, open = false), conversation("ahead", last = 500, open = false), conversation("open", last = 1, open = true)),
            emptyList(),
            IndexRowUpdate.Update(listOf(shard("0", 1)), emptyList()),
        )

        assertEquals(2, dao.purge(oldest = 10, newest = 100, index = IndexRowUpdate.Update(listOf(shard("0", 2)), emptyList())))

        assertEquals(setOf("kept", "open"), dao.rows(listOf("old", "kept", "ahead", "open")).map { it.id }.toSet())
        assertArrayEquals(byteArrayOf(2), dao.shards().single().payload)
    }

    @Test
    fun rowsReadsOnlyTheAskedRowsAndDeleteAllEmptiesBothTables() {
        val dao = db.conversationDao()
        dao.write(
            listOf(conversation("a", last = 1, open = false), conversation("b", last = 2, open = false), conversation("c", last = 3, open = true)),
            emptyList(),
            IndexRowUpdate.Update(listOf(shard("0", 1)), emptyList()),
        )

        assertEquals(setOf("a", "c"), dao.rows(listOf("a", "c", "gone")).map { it.id }.toSet())

        dao.deleteAll()
        assertTrue(dao.rows(listOf("a", "b", "c")).isEmpty())
        assertTrue(dao.shards().isEmpty())
    }

    // Design 9.5 and 9.7 of sub-project 7: Android's SQLite overwrites what a delete frees, so that an older copy of a shard
    // or of a deleted conversation is not left in the file's free pages. The connection that writes is the one that deletes.
    @Test
    fun theConnectionThatWritesOverwritesWhatItDeletes() {
        val database = db.openHelper.writableDatabase
        database.beginTransaction()
        try {
            database.query("PRAGMA secure_delete").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        } finally {
            database.endTransaction()
        }
    }

    private fun conversation(id: String, last: Long, open: Boolean) =
        ConversationRow(id, openedMillis = last - 1, lastMillis = last, open = open, commands = 2, payload = byteArrayOf(1))

    private fun shard(name: String, payload: Int) = ConversationIndexRow(name, byteArrayOf(payload.toByte()))
}
