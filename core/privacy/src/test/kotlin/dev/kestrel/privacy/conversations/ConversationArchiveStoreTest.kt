package dev.kestrel.privacy.conversations

import dev.kestrel.privacy.crypto.ErasableKey
import dev.kestrel.privacy.db.ConversationDao
import dev.kestrel.privacy.db.ConversationHeader
import dev.kestrel.privacy.db.ConversationRow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationArchiveStoreTest {
    private val dao = FakeConversationDao()
    private val key = MemoryKey()
    private var unknownFormats = 0
    private val store = ConversationArchiveStore(dao, key, onUnknownFormat = { unknownFormats++ }, dispatcher = Dispatchers.Unconfined)

    @Test
    fun everyConversationReadsBackNewestFirstWithItsPlainFieldsUntilItIsDeleted() = runTest {
        store.write(listOf(PlainConversation(closed("b", last = 25), "other".encodeToByteArray())))
        store.write(listOf(PlainConversation(opened("a", last = 20), "first".encodeToByteArray())))
        val changed = opened("a", last = 30).copy(commands = 3)
        store.write(listOf(PlainConversation(changed, "second".encodeToByteArray())))

        val stored = store.readAll().map { it as StoredConversation.Readable }
        assertEquals(listOf(changed, closed("b", last = 25)), stored.map { it.header })
        assertArrayEquals("second".encodeToByteArray(), stored[0].payload)
        assertArrayEquals("other".encodeToByteArray(), stored[1].payload)

        store.delete("a")
        assertEquals(listOf("b"), store.readAll().map { it.header.id })
    }

    @Test
    fun storesThePayloadEncrypted() = runTest {
        store.write(listOf(PlainConversation(closed("a", last = 1), "a secret request".encodeToByteArray())))

        assertFalse(dao.rows.getValue("a").payload.decodeToString(throwOnInvalidSequence = false).contains("secret"))
    }

    // Design 6.1 and 9.6 of sub-project 7: a switch closes one conversation and opens another in the same write.
    @Test
    fun oneWriteSwitchesTheOpenConversation() = runTest {
        store.write(listOf(PlainConversation(opened("a", last = 1), byteArrayOf(1)), PlainConversation(closed("b", last = 2), byteArrayOf(2))))
        val before = dao.transactions

        store.write(listOf(PlainConversation(closed("a", last = 3), byteArrayOf(1)), PlainConversation(opened("b", last = 3), byteArrayOf(2))))

        assertEquals(setOf("a" to false, "b" to true), store.readAll().map { it.header.id to it.header.open }.toSet())
        assertEquals(before + 1, dao.transactions)
    }

    @Test
    fun anOpenConversationClosesAnOpenRowThatTheCallerDoesNotKnow() = runTest {
        dao.rows["later"] = ConversationRow("later", 1, 2, open = true, commands = 1, payload = laterFormat())

        store.write(listOf(PlainConversation(opened("a", last = 3), byteArrayOf(1))))

        assertEquals(listOf("a" to true, "later" to false), store.readAll().map { it.header.id to it.header.open })
    }

    // Design 9.2 of sub-project 7: plan 7B's store deletes a payload that fails its check and reports it by its kind.
    @Test
    fun aPayloadMovedToAnotherConversationFailsItsCheckAndStaysForTheCallerToDelete() = runTest {
        store.write(listOf(PlainConversation(closed("a", last = 1), "words".encodeToByteArray())))
        val row = dao.rows.getValue("a")
        dao.rows["moved"] = ConversationRow("moved", row.openedMillis, row.lastMillis, open = false, commands = row.commands, payload = row.payload)

        val read = store.readAll().associateBy { it.header.id }

        assertTrue(read.getValue("a") is StoredConversation.Readable)
        assertTrue(read.getValue("moved") is StoredConversation.FailedCheck)
        assertEquals(closed("moved", last = 1), read.getValue("moved").header)
        assertTrue("moved" in dao.rows)
        assertEquals(0, unknownFormats)
    }

    @Test
    fun aConversationInAnUnknownFormatIsKeptAndReportedOncePerProcess() = runTest {
        dao.rows["later"] = ConversationRow("later", 1, 2, open = false, commands = 1, payload = laterFormat())

        assertTrue(store.readAll().single() is StoredConversation.UnknownFormat)
        assertTrue(store.readAll().single() is StoredConversation.UnknownFormat)

        assertTrue("later" in dao.rows)
        assertEquals(1, unknownFormats)
    }

    @Test
    fun aReportOfAnUnknownFormatThatFailsIsMadeAgainAtTheNextRead() = runTest {
        var reports = 0
        val store = ConversationArchiveStore(dao, key, onUnknownFormat = { if (++reports == 1) throw IOException("the log is unavailable") }, dispatcher = Dispatchers.Unconfined)
        dao.rows["later"] = ConversationRow("later", 1, 2, open = false, commands = 1, payload = laterFormat())

        assertTrue(runCatching { store.readAll() }.exceptionOrNull() is IOException)
        assertTrue(store.readAll().single() is StoredConversation.UnknownFormat)
        assertTrue(store.readAll().single() is StoredConversation.UnknownFormat)

        assertEquals(2, reports)
    }

    // Design 9.7 of sub-project 7: after "delete history", not even a copy of the database file can be read.
    @Test
    fun deleteAllErasesTheRowsAndTheKeySoThatACopyOfARowCannotBeRead() = runTest {
        store.write(listOf(PlainConversation(closed("a", last = 1), "words".encodeToByteArray())))
        val copy = dao.rows.getValue("a")

        store.deleteAll()

        assertTrue(dao.rows.isEmpty())
        assertEquals(1, key.erased)
        dao.rows["a"] = copy
        assertTrue(store.readAll().single() is StoredConversation.FailedCheck)
    }

    // Without the key's guard, the key could be erased between a write's encryption and its row, which no key would then open.
    @Test
    fun deleteAllWaitsForAWriteThatHasStartedSoThatNoRowOutlivesTheKey() = runTest {
        val gate = CompletableDeferred<Unit>()
        dao.beforeUpsert = { gate.await() }
        val write = launch { store.write(listOf(PlainConversation(closed("a", last = 1), byteArrayOf(1)))) }
        runCurrent()
        val delete = launch { store.deleteAll() }
        runCurrent()
        assertEquals(0, key.erased)

        gate.complete(Unit)
        write.join()
        delete.join()

        assertEquals(1, key.erased)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun runsTheCipherOnTheStoresDispatcher() = runTest {
        val threads = CopyOnWriteArrayList<Thread>()
        val watched = object : ErasableKey {
            override val guard = key.guard

            override fun get(): SecretKey = key.get().also { threads += Thread.currentThread() }

            override fun erase() = key.erase()
        }
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val store = ConversationArchiveStore(dao, watched, dispatcher = dispatcher)
            store.write(listOf(PlainConversation(closed("a", last = 1), byteArrayOf(1))))
            store.readAll()

            assertEquals(setOf(withContext(dispatcher) { Thread.currentThread() }), threads.toSet())
        }
    }

    private fun opened(id: String, last: Long) = ConversationHeader(id, openedMillis = last - 1, lastMillis = last, open = true, commands = 1)

    private fun closed(id: String, last: Long) = opened(id, last).copy(open = false)

    /** A later version's ciphertext: its first byte names a format that this version does not know. */
    private fun laterFormat() = byteArrayOf(2) + ByteArray(40)
}

/** An [ErasableKey] in memory; [erase] makes a new key, as Keystore does at the next use. */
private class MemoryKey : ErasableKey {
    override val guard = Mutex()
    var erased = 0
        private set
    private var key = newKey()

    override fun get(): SecretKey = key

    override fun erase() {
        erased++
        key = newKey()
    }

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
}

/**
 * Mimics the SQL in [ConversationDao]; KestrelDatabaseTest checks the real queries and the transaction on a device. Its
 * write is the DAO's own, so these tests run it; [transactions] counts its calls.
 */
private class FakeConversationDao : ConversationDao() {
    val rows = linkedMapOf<String, ConversationRow>()
    var beforeUpsert: suspend () -> Unit = {}
    var transactions = 0

    override suspend fun write(rows: List<ConversationRow>) {
        transactions++
        super.write(rows)
    }

    override suspend fun all(): List<ConversationRow> = rows.values.sortedByDescending { it.lastMillis }

    override suspend fun delete(id: String): Int = if (rows.remove(id) != null) 1 else 0

    override suspend fun deleteOutside(oldest: Long, newest: Long): Int {
        val outside = rows.values.filter { !it.open && (it.lastMillis < oldest || it.lastMillis > newest) }.map { it.id }
        outside.forEach { rows.remove(it) }
        return outside.size
    }

    override suspend fun deleteAll(): Int = rows.size.also { rows.clear() }

    override suspend fun upsert(row: ConversationRow) {
        beforeUpsert()
        rows[row.id] = row
    }

    override suspend fun closeOthers(id: String): Int {
        val others = rows.values.filter { it.open && it.id != id }
        others.forEach { rows[it.id] = ConversationRow(it.id, it.openedMillis, it.lastMillis, open = false, commands = it.commands, payload = it.payload) }
        return others.size
    }
}
