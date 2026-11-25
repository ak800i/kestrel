package dev.kestrel.privacy.conversations

import dev.kestrel.privacy.crypto.ErasableKey
import dev.kestrel.privacy.db.ConversationDao
import dev.kestrel.privacy.db.ConversationHeader
import dev.kestrel.privacy.db.ConversationIndexRow
import dev.kestrel.privacy.db.ConversationRow
import dev.kestrel.privacy.db.ConversationStamp
import dev.kestrel.privacy.db.IndexRowUpdate
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationArchiveStoreTest {
    private val dao = FakeConversationDao()
    private val key = MemoryKey()
    private var unknownFormats = 0
    private val store = ConversationArchiveStore(dao, key, onUnknownFormat = { unknownFormats++ }, dispatcher = Dispatchers.Unconfined)

    private suspend fun write(vararg conversations: PlainConversation, deleted: List<String> = emptyList(), index: IndexUpdate = IndexUpdate.Keep) =
        store.write(ConversationChange(conversations.toList(), deleted, index))

    @Test
    fun everyConversationReadsBackWithItsPlainFieldsUntilItIsDeleted() = runTest {
        write(PlainConversation(closed("b", last = 25), "other".encodeToByteArray()))
        write(PlainConversation(opened("a", last = 20), "first".encodeToByteArray()))
        val changed = opened("a", last = 30).copy(commands = 3)
        write(PlainConversation(changed, "second".encodeToByteArray()), PlainConversation(closed("c", last = 5), "older".encodeToByteArray()))

        val stored = store.read(listOf("a", "b")).associateBy { it.header.id }.mapValues { it.value as StoredConversation.Readable }
        assertEquals(changed, stored.getValue("a").header)
        assertEquals(closed("b", last = 25), stored.getValue("b").header)
        assertArrayEquals("second".encodeToByteArray(), stored.getValue("a").payload)
        assertArrayEquals("other".encodeToByteArray(), stored.getValue("b").payload)
        assertEquals(listOf("c", "b"), store.closedIds())

        write(deleted = listOf("a"))
        assertTrue(store.read(listOf("a")).isEmpty())
        assertEquals(null, store.open())
    }

    @Test
    fun storesThePayloadsAndTheIndexEncrypted() = runTest {
        write(PlainConversation(closed("a", last = 1), "a secret request".encodeToByteArray()), index = IndexUpdate.Update(mapOf("0" to "a secret word".encodeToByteArray()), emptySet()))

        assertFalse(dao.rows.getValue("a").payload.decodeToString(throwOnInvalidSequence = false).contains("secret"))
        assertFalse(dao.shards.getValue("0").payload.decodeToString(throwOnInvalidSequence = false).contains("secret"))
    }

    // Design 9.2 of sub-project 7: a restore reads the open conversation alone; the shards and the stamp are read apart.
    @Test
    fun theOpenConversationTheShardsAndTheStampOfTheClosedRowsAreReadApart() = runTest {
        assertTrue(store.shards().isEmpty())

        write(
            PlainConversation(closed("a", last = 10), byteArrayOf(1)),
            PlainConversation(closed("b", last = 30), byteArrayOf(2)),
            PlainConversation(opened("c", last = 40), "open".encodeToByteArray()),
            index = IndexUpdate.Update(mapOf("0" to "zero".encodeToByteArray(), "1" to "one".encodeToByteArray()), emptySet()),
        )
        assertEquals("open", (store.open() as StoredConversation.Readable).payload.decodeToString())
        assertEquals(mapOf("0" to "zero", "1" to "one"), store.shards().associate { it.name to (it as StoredShard.Readable).payload.decodeToString() })
        assertEquals(ConversationStamp(closed = 2, newest = 30, total = 40), store.stamp())

        write(index = IndexUpdate.Update(mapOf("1" to "new".encodeToByteArray()), setOf("0")))
        assertEquals(mapOf("1" to "new"), store.shards().associate { it.name to (it as StoredShard.Readable).payload.decodeToString() })
        write(index = IndexUpdate.Replace(emptyMap()))
        assertTrue(store.shards().isEmpty())
    }

    @Test
    fun aReadGivesOnlyTheConversationsThatItAsksFor() = runTest {
        write(PlainConversation(closed("a", last = 1), byteArrayOf(1)), PlainConversation(closed("b", last = 2), byteArrayOf(2)), PlainConversation(closed("c", last = 3), byteArrayOf(3)))

        assertEquals(setOf("a", "c"), store.read(listOf("a", "c", "gone")).map { it.header.id }.toSet())
    }

    // Design 6.1 and 9.6 of sub-project 7: a switch closes one conversation and opens another in the same write.
    @Test
    fun oneWriteSwitchesTheOpenConversationAndReplacesTheIndex() = runTest {
        write(PlainConversation(opened("a", last = 1), byteArrayOf(1)), PlainConversation(closed("b", last = 2), byteArrayOf(2)), index = IndexUpdate.Replace(mapOf("0" to byteArrayOf(5), "1" to byteArrayOf(6))))
        val before = dao.transactions

        write(PlainConversation(closed("a", last = 3), byteArrayOf(1)), PlainConversation(opened("b", last = 3), byteArrayOf(2)), index = IndexUpdate.Replace(mapOf("2" to byteArrayOf(7))))

        assertEquals(setOf("a" to false, "b" to true), store.read(listOf("a", "b")).map { it.header.id to it.header.open }.toSet())
        assertEquals(listOf("2"), store.shards().map { it.name })
        assertArrayEquals(byteArrayOf(7), (store.shards().single() as StoredShard.Readable).payload)
        assertEquals(before + 1, dao.transactions)
    }

    // Design 9.3 of sub-project 7: retention deletes by the table's own times, in one transaction with the index.
    @Test
    fun purgeDeletesTheClosedRowsOutsideTheWindowWithTheIndexInOneTransaction() = runTest {
        write(
            PlainConversation(closed("old", last = 5), byteArrayOf(1)),
            PlainConversation(closed("kept", last = 50), byteArrayOf(2)),
            PlainConversation(closed("ahead", last = 500), byteArrayOf(3)),
            PlainConversation(opened("open", last = 1), byteArrayOf(4)),
            index = IndexUpdate.Update(mapOf("0" to byteArrayOf(1)), emptySet()),
        )
        val before = dao.transactions

        assertEquals(2, store.purge(oldest = 10, newest = 100, index = IndexUpdate.Update(mapOf("0" to byteArrayOf(2)), emptySet())))

        assertEquals(setOf("kept", "open"), dao.rows.keys)
        assertArrayEquals(byteArrayOf(2), (store.shards().single() as StoredShard.Readable).payload)
        assertEquals(before + 1, dao.transactions)
    }

    @Test
    fun anOpenConversationClosesAnOpenRowThatTheCallerDoesNotKnow() = runTest {
        dao.rows["later"] = ConversationRow("later", 1, 2, open = true, commands = 1, payload = laterFormat())

        write(PlainConversation(opened("a", last = 3), byteArrayOf(1)))

        assertEquals(listOf("a" to true, "later" to false), store.read(listOf("a", "later")).map { it.header.id to it.header.open })
    }

    // Design 9.2 of sub-project 7: plan 7B's store deletes a payload that fails its check and reports it by its kind.
    @Test
    fun aPayloadMovedToAnotherConversationFailsItsCheckAndStaysForTheCallerToDelete() = runTest {
        write(PlainConversation(closed("a", last = 1), "words".encodeToByteArray()))
        val row = dao.rows.getValue("a")
        dao.rows["moved"] = ConversationRow("moved", row.openedMillis, row.lastMillis, open = false, commands = row.commands, payload = row.payload)

        val read = store.read(listOf("a", "moved")).associateBy { it.header.id }

        assertTrue(read.getValue("a") is StoredConversation.Readable)
        assertTrue(read.getValue("moved") is StoredConversation.FailedCheck)
        assertEquals(closed("moved", last = 1), read.getValue("moved").header)
        assertTrue("moved" in dao.rows)
        assertEquals(0, unknownFormats)
    }

    // The shards and the conversations are bound to their places: no payload opens in another's.
    @Test
    fun aShardInAnotherPlaceOrInAnotherFormatCannotBeRead() = runTest {
        write(PlainConversation(closed("a", last = 1), "words".encodeToByteArray()), index = IndexUpdate.Update(mapOf("0" to "zero".encodeToByteArray(), "1" to "one".encodeToByteArray()), emptySet()))
        val zero = dao.shards.getValue("0").payload
        val one = dao.shards.getValue("1").payload
        val conversation = dao.rows.getValue("a").payload

        dao.rows["a"] = ConversationRow("a", 0, 1, open = false, commands = 1, payload = zero)
        dao.shards["0"] = ConversationIndexRow("0", one)
        dao.shards["1"] = ConversationIndexRow("1", conversation)
        assertTrue(store.read(listOf("a")).single() is StoredConversation.FailedCheck)
        assertTrue(store.shards().all { it is StoredShard.Unreadable })

        dao.shards["0"] = ConversationIndexRow("0", laterFormat())
        assertTrue(store.shards().first { it.name == "0" } is StoredShard.Unreadable)
        assertEquals(0, unknownFormats)
    }

    @Test
    fun aConversationInAnUnknownFormatIsKeptAndReportedOncePerProcess() = runTest {
        dao.rows["later"] = ConversationRow("later", 1, 2, open = false, commands = 1, payload = laterFormat())

        assertTrue(store.read(listOf("later")).single() is StoredConversation.UnknownFormat)
        assertTrue(store.read(listOf("later")).single() is StoredConversation.UnknownFormat)

        assertTrue("later" in dao.rows)
        assertEquals(1, unknownFormats)
    }

    @Test
    fun aReportOfAnUnknownFormatThatFailsIsMadeAgainAtTheNextRead() = runTest {
        var reports = 0
        val store = ConversationArchiveStore(dao, key, onUnknownFormat = { if (++reports == 1) throw IOException("the log is unavailable") }, dispatcher = Dispatchers.Unconfined)
        dao.rows["later"] = ConversationRow("later", 1, 2, open = true, commands = 1, payload = laterFormat())

        assertTrue(runCatching { store.open() }.exceptionOrNull() is IOException)
        assertTrue(store.open() is StoredConversation.UnknownFormat)
        assertTrue(store.read(listOf("later")).single() is StoredConversation.UnknownFormat)

        assertEquals(2, reports)
    }

    // Design 9.7 of sub-project 7: after "delete history", not even a copy of the database file can be read. The rows go
    // first, so that a deletion cut short leaves every row readable and no note of a row that failed.
    @Test
    fun deleteAllDeletesTheRowsAndTheIndexAndThenErasesTheKeySoThatACopyCannotBeRead() = runTest {
        write(PlainConversation(closed("a", last = 1), "words".encodeToByteArray()), index = IndexUpdate.Update(mapOf("0" to byteArrayOf(1)), emptySet()))
        val copy = dao.rows.getValue("a")
        val shard = dao.shards.getValue("0")
        var erasedBeforeTheRowsWent = true
        dao.beforeDeleteAll = { erasedBeforeTheRowsWent = key.erased > 0 }

        store.deleteAll()

        assertFalse(erasedBeforeTheRowsWent)
        assertEquals(1, key.erased)
        assertTrue(dao.rows.isEmpty())
        assertTrue(dao.shards.isEmpty())
        dao.rows["a"] = copy
        dao.shards["0"] = shard
        assertTrue(store.read(listOf("a")).single() is StoredConversation.FailedCheck)
        assertTrue(store.shards().single() is StoredShard.Unreadable)
    }

    // A process that ended between the deletion of the rows and the erasure of the key left the key, which must not encrypt
    // the next conversation: the first change of the next process erases it, and only that one.
    @Test
    fun theFirstChangeOfAProcessErasesTheKeyThatAnEmptyArchiveKept() = runTest {
        write(PlainConversation(closed("a", last = 1), "words".encodeToByteArray()))
        dao.rows.clear()
        val next = ConversationArchiveStore(dao, key, dispatcher = Dispatchers.Unconfined)

        assertTrue(next.read(listOf("a")).isEmpty())
        assertEquals(0, key.erased)
        next.write(ConversationChange(listOf(PlainConversation(closed("b", last = 2), "new".encodeToByteArray())), emptyList(), IndexUpdate.Keep))
        assertEquals(1, key.erased)
        next.write(ConversationChange(listOf(PlainConversation(closed("c", last = 3), "more".encodeToByteArray())), emptyList(), IndexUpdate.Keep))
        next.purge(oldest = 0, newest = 10, IndexUpdate.Keep)
        assertEquals(1, key.erased)
        assertEquals(2, next.read(listOf("b", "c")).filterIsInstance<StoredConversation.Readable>().size)

        // An archive that holds rows keeps its key at a process's first change.
        ConversationArchiveStore(dao, key, dispatcher = Dispatchers.Unconfined).write(ConversationChange(emptyList(), listOf("c"), IndexUpdate.Keep))
        assertEquals(1, key.erased)
        assertTrue(next.read(listOf("b")).single() is StoredConversation.Readable)
    }

    // Without the key's guard, the key could be erased between a write's encryption and its row, which no key would then
    // open. The pause can only let a wrong order pass, never fail a right one.
    @Test
    fun deleteAllWaitsForAWriteThatHasStartedSoThatNoRowOutlivesTheKey() = runTest {
        val written = CountDownLatch(1)
        val gate = CountDownLatch(1)
        dao.beforeUpsert = { written.countDown(); gate.await(5, TimeUnit.SECONDS) }
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val store = ConversationArchiveStore(dao, key, dispatcher = dispatcher)
            val write = launch(Dispatchers.Default) { store.write(ConversationChange(listOf(PlainConversation(closed("a", last = 1), byteArrayOf(1))), emptyList(), IndexUpdate.Keep)) }
            assertTrue(written.await(5, TimeUnit.SECONDS))
            val delete = launch(Dispatchers.Default) { store.deleteAll() }
            Thread.sleep(100)
            assertEquals(0, key.erased)

            gate.countDown()
            write.join()
            delete.join()
        }

        assertEquals(1, key.erased)
        assertTrue(dao.rows.isEmpty())
    }

    // Design R3 of sub-project 7: a write that holds the key's guard never holds up a read, such as a rebuild's.
    @Test
    fun aReadDoesNotWaitForTheKeysGuard() = runTest {
        write(PlainConversation(closed("a", last = 1), byteArrayOf(1)))
        key.guard.lock()
        try {
            withTimeout(1_000) {
                assertEquals(listOf("a"), store.read(listOf("a")).map { it.header.id })
                assertTrue(store.shards().isEmpty())
                assertEquals(listOf("a"), store.closedIds())
            }
        } finally {
            key.guard.unlock()
        }
    }

    // Design 9.2 of sub-project 7: "delete history" stops a rebuild, whose read then decrypts no more rows.
    @Test
    fun aCancelledReadStopsBetweenTwoRows() = runTest {
        write(PlainConversation(closed("a", last = 1), byteArrayOf(1)), PlainConversation(closed("b", last = 2), byteArrayOf(2)), PlainConversation(closed("c", last = 3), byteArrayOf(3)))
        var decrypted = 0
        lateinit var reading: Job
        val watched = object : ErasableKey {
            override val guard = key.guard

            override fun get(): SecretKey = key.get()

            override fun <T> withExisting(block: (SecretKey?) -> T): T = key.withExisting(block).also { if (++decrypted == 1) reading.cancel() }

            override fun erase() = key.erase()
        }
        val store = ConversationArchiveStore(dao, watched, dispatcher = Dispatchers.Unconfined)
        reading = launch(start = CoroutineStart.LAZY) { store.read(listOf("a", "b", "c")) }

        reading.join()

        assertTrue(reading.isCancelled)
        assertEquals(1, decrypted)
    }

    // Design R3 of sub-project 7: a Keystore operation right after a hop to another thread costs about twice as much.
    @Test
    fun eachReadRunsItsQueryAndItsDecryptionOnOneThreadOfTheStoresDispatcher() = runTest {
        val threads = CopyOnWriteArrayList<Thread>()
        val watched = object : ErasableKey {
            override val guard = key.guard

            override fun get(): SecretKey = key.get()

            override fun <T> withExisting(block: (SecretKey?) -> T): T = key.withExisting(block).also { threads += Thread.currentThread() }

            override fun erase() = key.erase()
        }
        dao.onQuery = { threads += Thread.currentThread() }
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val store = ConversationArchiveStore(dao, watched, dispatcher = dispatcher)
            store.write(ConversationChange(listOf(PlainConversation(opened("a", last = 1), byteArrayOf(1))), emptyList(), IndexUpdate.Update(mapOf("0" to byteArrayOf(2)), emptySet())))
            threads.clear()
            store.open()
            store.shards()
            store.read(listOf("a"))

            assertEquals(6, threads.size)
            assertEquals(setOf(withContext(dispatcher) { Thread.currentThread() }), threads.toSet())
        }
    }

    // Design 9.7 of sub-project 7: a read never makes the key. Between the key's erasure and the rows' deletion, a read that
    // overlaps "delete history" finds every row failing its check, no readable shard, and a later format still unknown.
    @Test
    fun aReadWithoutTheKeyMakesNoKeyAndFindsTheRowsUnreadable() = runTest {
        write(
            PlainConversation(opened("a", last = 2), byteArrayOf(1)),
            PlainConversation(closed("b", last = 1), byteArrayOf(2)),
            index = IndexUpdate.Update(mapOf("0" to byteArrayOf(3)), emptySet()),
        )
        dao.rows["later"] = ConversationRow("later", 1, 2, open = false, commands = 1, payload = laterFormat())
        key.erase()

        assertTrue(store.open() is StoredConversation.FailedCheck)
        assertEquals(
            mapOf("a" to StoredConversation.FailedCheck::class, "b" to StoredConversation.FailedCheck::class, "later" to StoredConversation.UnknownFormat::class),
            store.read(listOf("a", "b", "later")).associate { it.header.id to it::class },
        )
        assertTrue(store.shards().single() is StoredShard.Unreadable)
        assertEquals(1, key.made)
        assertFalse(key.exists)
    }

    private fun opened(id: String, last: Long) = ConversationHeader(id, openedMillis = last - 1, lastMillis = last, open = true, commands = 1)

    private fun closed(id: String, last: Long) = opened(id, last).copy(open = false)

    /** A later version's ciphertext: its first byte names a format that this version does not know. */
    private fun laterFormat() = byteArrayOf(2) + ByteArray(40)
}

/**
 * An [ErasableKey] in memory, which [get] makes when there is none, as Keystore does, and [erase] deletes; [erased] counts
 * the erasures of a key that existed, so that a first change that finds no key to erase does not count.
 */
private class MemoryKey : ErasableKey {
    override val guard = Mutex()

    @Volatile
    var erased = 0
        private set

    @Volatile
    var made = 0
        private set
    private var key: SecretKey? = null

    val exists: Boolean
        @Synchronized get() = key != null

    @Synchronized
    override fun get(): SecretKey = key ?: newKey().also {
        key = it
        made++
    }

    @Synchronized
    override fun <T> withExisting(block: (SecretKey?) -> T): T = block(key)

    @Synchronized
    override fun erase() {
        if (key != null) erased++
        key = null
    }

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
}

/**
 * Mimics the SQL in [ConversationDao]; KestrelDatabaseTest checks the real queries and the transactions on a device. Its
 * write, purge and deleteAll are the DAO's own, so these tests run them; [transactions] counts the writes and the
 * purges, and [onQuery] hears each query that reads rows.
 */
private class FakeConversationDao : ConversationDao() {
    val rows = linkedMapOf<String, ConversationRow>()
    val shards = linkedMapOf<String, ConversationIndexRow>()
    var beforeUpsert: () -> Unit = {}
    var beforeDeleteAll: () -> Unit = {}
    var onQuery: () -> Unit = {}
    var transactions = 0

    override fun write(rows: List<ConversationRow>, deleted: List<String>, index: IndexRowUpdate) {
        transactions++
        super.write(rows, deleted, index)
    }

    override fun purge(oldest: Long, newest: Long, index: IndexRowUpdate): Int {
        transactions++
        return super.purge(oldest, newest, index)
    }

    override fun deleteAll() {
        beforeDeleteAll()
        super.deleteAll()
    }

    override fun open(): ConversationRow? = rows.values.firstOrNull { it.open }.also { onQuery() }

    override fun rows(ids: List<String>): List<ConversationRow> = ids.mapNotNull { rows[it] }.also { onQuery() }

    override fun closedIds(): List<String> = rows.values.filter { !it.open }.sortedBy { it.lastMillis }.map { it.id }

    override fun shards(): List<ConversationIndexRow> = shards.values.toList().also { onQuery() }

    override fun isEmpty(): Boolean = rows.isEmpty() && shards.isEmpty()

    override fun stamp(): ConversationStamp {
        val closed = rows.values.filter { !it.open }.map { it.lastMillis }
        return ConversationStamp(closed.size, closed.maxOrNull() ?: 0, closed.sum())
    }

    override fun delete(id: String): Int = if (rows.remove(id) != null) 1 else 0

    override fun deleteClosedOutside(oldest: Long, newest: Long): Int {
        val gone = rows.values.filter { !it.open && (it.lastMillis < oldest || it.lastMillis > newest) }.map { it.id }
        gone.forEach { rows.remove(it) }
        return gone.size
    }

    override fun upsert(row: ConversationRow) {
        beforeUpsert()
        rows[row.id] = row
    }

    override fun closeOthers(id: String): Int {
        val others = rows.values.filter { it.open && it.id != id }
        others.forEach { rows[it.id] = ConversationRow(it.id, it.openedMillis, it.lastMillis, open = false, commands = it.commands, payload = it.payload) }
        return others.size
    }

    override fun upsertIndex(row: ConversationIndexRow) {
        shards[row.name] = row
    }

    override fun deleteIndex(name: String): Int = if (shards.remove(name) != null) 1 else 0

    override fun deleteConversations(): Int = rows.size.also { rows.clear() }

    override fun deleteIndexes(): Int = shards.size.also { shards.clear() }
}
