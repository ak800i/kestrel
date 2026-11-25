package dev.kestrel.app.conversation

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.conversation.model.CommandRecord
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.ConversationSnapshot
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.store.ArchiveChange
import dev.kestrel.conversation.store.ArchiveEntry
import dev.kestrel.conversation.store.ArchiveErasure
import dev.kestrel.conversation.store.ArchiveHeader
import dev.kestrel.conversation.store.ArchiveNote
import dev.kestrel.conversation.store.ArchiveRow
import dev.kestrel.conversation.store.ArchivedConversationStore
import dev.kestrel.conversation.store.IndexStamp
import dev.kestrel.conversation.store.IndexWrite
import dev.kestrel.conversation.store.ShardRead
import dev.kestrel.privacy.conversations.ConversationArchiveStore
import dev.kestrel.privacy.crypto.KeystoreErasableKey
import dev.kestrel.privacy.db.ConversationIndexRow
import dev.kestrel.privacy.db.ConversationRow
import dev.kestrel.privacy.db.IndexRowUpdate
import dev.kestrel.privacy.db.KestrelDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

/**
 * The archive's port on the real encrypted tables and the device's Keystore (design 9.1 and 9.2 of sub-project 7). It uses
 * a database in memory and a test key, never Kestrel's own, and made-up texts only.
 */
@RunWith(AndroidJUnit4::class)
class RoomRecordArchiveTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val alias = "kestrel.test.conversations.${System.nanoTime()}"
    private val database = Room.inMemoryDatabaseBuilder(context, KestrelDatabase::class.java).build()
    private val table = ConversationArchiveStore(database.conversationDao(), KeystoreErasableKey.of(alias))
    private val archive = RoomRecordArchive(table) { table.deleteAll() }

    private val past = ArchiveHeader("0123456789abcdef0123456789abcdef", openedMillis = 10, lastMillis = 20, open = false, commands = 2)
    private val current = ArchiveHeader("fedcba9876543210fedcba9876543210", openedMillis = 30, lastMillis = 40, open = true, commands = 1)

    @After
    fun cleanUp() {
        database.close()
        KeystoreErasableKey.of(alias).erase()
    }

    @Test
    fun everyFieldTravelsThroughTheEncryptedTable() = runBlocking {
        archive.write(ArchiveChange(rows = listOf(row(past, """{"request":"Пошаљи Мили поруку 🙂"}"""), row(current, """{"request":"Turn on dark mode"}"""))))

        val read = archive.read(listOf(past.id, current.id, "gone")).associateBy { it.header.id }
        assertEquals(setOf(past, current), read.values.map { it.header }.toSet())
        assertEquals("""{"request":"Пошаљи Мили поруку 🙂"}""", (read.getValue(past.id) as ArchiveEntry.Readable).payload.decodeToString())
        // The rows come in the order of the ids, which is neither the table's order of its keys nor the order of the writes.
        assertEquals(listOf(current.id, past.id), archive.read(listOf(current.id, "gone", past.id)).map { it.header.id })
        assertEquals(current, archive.readOpen()!!.header)
        assertEquals("""{"request":"Turn on dark mode"}""", (archive.readOpen() as ArchiveEntry.Readable).payload.decodeToString())
        assertEquals(listOf(past.id), archive.closedIds())
        assertFalse(database.conversationDao().rows(listOf(past.id)).single().payload.decodeToString(throwOnInvalidSequence = false).contains("Мили"))

        // A switch (design 9.6): the open conversation closes and the past one opens, in one write.
        archive.write(ArchiveChange(rows = listOf(row(current.copy(open = false, lastMillis = 50), "{}"), row(past.copy(open = true, lastMillis = 50), "{}"))))
        assertEquals(setOf(current.id to false, past.id to true), archive.read(listOf(past.id, current.id)).map { it.header.id to it.header.open }.toSet())

        // Retention (design 9.3) deletes the closed rows outside its window, by the table's times, never the open one.
        assertEquals(1, archive.purge(oldest = 60, newest = 100, index = IndexWrite.Keep))
        assertEquals(listOf(past.id), archive.read(listOf(past.id, current.id)).map { it.header.id })

        archive.write(ArchiveChange(deleted = setOf(past.id)))
        assertNull(archive.readOpen())
    }

    // Design 9.2 of sub-project 7: the shards travel encrypted, each under its name, and the closed rows' stamp is read apart.
    @Test
    fun theShardsTravelEncryptedAndTheStampCountsTheClosedRows() = runBlocking {
        assertTrue(archive.readShards().isEmpty())

        archive.write(ArchiveChange(rows = listOf(row(past, "{}"), row(current, "{}")), index = IndexWrite.Replace(mapOf("0" to "Мила Ambar".encodeToByteArray(), "1" to "Viber".encodeToByteArray()))))

        assertEquals(mapOf("0" to "Мила Ambar", "1" to "Viber"), archive.readShards().associate { it.name to (it as ShardRead.Readable).bytes.decodeToString() })
        assertEquals(IndexStamp(closed = 1, newest = 20, total = 20), archive.readStamp())
        assertFalse(database.conversationDao().shards().any { it.payload.decodeToString(throwOnInvalidSequence = false).contains("Ambar") })

        archive.write(ArchiveChange(index = IndexWrite.Update(mapOf("1" to "Signal".encodeToByteArray()), setOf("0"))))
        assertEquals(mapOf("1" to "Signal"), archive.readShards().associate { it.name to (it as ShardRead.Readable).bytes.decodeToString() })
        archive.write(ArchiveChange(index = IndexWrite.Replace(emptyMap())))
        assertTrue(archive.readShards().isEmpty())
    }

    // Design 9.2 of sub-project 7: plan 7B's store deletes an Unreadable entry, leaves an Unknown one out and rebuilds an unreadable index.
    @Test
    fun eachKindOfUnreadableRowBecomesItsKindOfEntry() = runBlocking {
        archive.write(ArchiveChange(rows = listOf(row(past, "{}")), index = IndexWrite.Replace(mapOf("0" to byteArrayOf(1)))))
        val dao = database.conversationDao()
        val sealed = dao.rows(listOf(past.id)).single().payload
        // A later version's ciphertext: its first byte names a format that this version does not know.
        val later = byteArrayOf(2) + ByteArray(40)
        dao.write(
            listOf(ConversationRow("moved", 1, 2, open = false, commands = 1, payload = sealed), ConversationRow("later", 1, 2, open = false, commands = 1, payload = later)),
            emptyList(),
            IndexRowUpdate.Update(listOf(ConversationIndexRow("0", sealed)), emptyList()),
        )

        val read = archive.read(listOf(past.id, "moved", "later")).associateBy { it.header.id }

        assertTrue(read.getValue(past.id) is ArchiveEntry.Readable)
        assertTrue(read.getValue("moved") is ArchiveEntry.Unreadable)
        assertEquals(ArchiveHeader("moved", 1, 2, open = false, commands = 1), read.getValue("moved").header)
        assertTrue(read.getValue("later") is ArchiveEntry.Unknown)
        assertTrue(archive.readShards().single() is ShardRead.Unreadable)
    }

    // Design 9.7 of sub-project 7: deleting through the port erases the archive's key with its rows and its index.
    @Test
    fun deleteAllErasesTheRowsTheIndexAndTheKey() = runBlocking {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        archive.write(ArchiveChange(rows = listOf(row(current, "{}")), index = IndexWrite.Replace(mapOf("0" to byteArrayOf(1)))))
        assertTrue(keyStore.containsAlias(alias))

        assertEquals(ArchiveErasure.KEY_ERASED, archive.deleteAll())

        assertNull(archive.readOpen())
        assertTrue(archive.readShards().isEmpty())
        assertFalse(keyStore.containsAlias(alias))
    }

    // Design 6.1 of sub-project 7: the table's open column is the one truth about which conversation is open, and the
    // snapshot's JSON does not hold it. When a second open conversation is written, the table closes the first, and the
    // index takes it in the same transaction; after a restart, the store reads the first as closed, and the index that it
    // reads is in step with the table's stamp, so that nothing is rebuilt.
    @Test
    fun theTablesOpenColumnDecidesWhichConversationIsOpen() = runBlocking {
        val first = ConversationSnapshot(ConversationId(past.id), openedAtWall = 10, lastWall = 20, open = true, testMode = true)
        val second = ConversationSnapshot(ConversationId(current.id), openedAtWall = 30, lastWall = 40, open = true, testMode = true)
        val writing = ArchivedConversationStore(archive, this) {}
        writing.save(first)
        writing.save(second)
        // The first store's index has settled, so that the restart reads it.
        assertEquals(listOf(first.id), writing.list().map { it.id })
        val notes = mutableListOf<ArchiveNote>()

        val restarted = ArchivedConversationStore(archive, this) { notes += it }

        assertEquals(listOf(current.id to true, past.id to false), archive.read(listOf(current.id, past.id)).map { it.header.id to it.header.open })
        assertEquals(second.id, restarted.open()?.id)
        assertEquals(listOf(first.id), restarted.list().map { it.id })
        assertTrue(notes.isEmpty())
    }

    // Design 9.2 of sub-project 7: a store on the real archive rebuilds a lost index from the encrypted bodies.
    @Test
    fun aStoreOnTheEncryptedArchiveRebuildsALostIndex() = runBlocking {
        val writing = ArchivedConversationStore(archive, this) {}
        for (n in 1..3) {
            val at = n * 1_000L
            writing.save(ConversationSnapshot(ConversationId("$n".padStart(32, '0')), at, at, open = false, testMode = true, records = listOf(CommandRecord(1, at, InputChannel.TEXT, "Request $n"))))
        }
        val listed = writing.list()
        archive.write(ArchiveChange(index = IndexWrite.Replace(emptyMap())))
        val notes = mutableListOf<ArchiveNote>()

        val restarted = ArchivedConversationStore(archive, this) { notes += it }

        assertEquals(listed, restarted.list())
        assertEquals(listOf(ArchiveNote.INDEX_REBUILT), notes)
        assertTrue(archive.readShards().isNotEmpty())
    }

    private fun row(header: ArchiveHeader, json: String) = ArchiveRow(header, json.encodeToByteArray())
}
