package dev.kestrel.privacy.conversations

import dev.kestrel.privacy.crypto.ErasableKey
import dev.kestrel.privacy.crypto.KeyErasure
import dev.kestrel.privacy.db.ConversationDao
import dev.kestrel.privacy.db.ConversationIndexRow
import dev.kestrel.privacy.db.IndexRowRead
import dev.kestrel.privacy.db.ConversationRow
import dev.kestrel.privacy.db.ConversationRowRead
import dev.kestrel.privacy.db.ConversationStamp
import dev.kestrel.privacy.db.IndexRowUpdate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.KeyStoreException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.coroutines.CoroutineContext

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

    // Plan 7F: "Delete all past conversations" deletes every closed row but the one that it keeps, in one transaction with
    // the index, and keeps the open row and the key, which still decrypts what stays.
    @Test
    fun deleteClosedDeletesEveryClosedRowButTheKeptOneWithTheIndexInOneTransactionAndKeepsTheKey() = runTest {
        write(
            PlainConversation(closed("old", last = 5), byteArrayOf(1)),
            PlainConversation(closed("kept", last = 50), byteArrayOf(2)),
            PlainConversation(closed("new", last = 500), byteArrayOf(3)),
            PlainConversation(opened("open", last = 1), byteArrayOf(4)),
            index = IndexUpdate.Update(mapOf("0" to byteArrayOf(1)), emptySet()),
        )
        val before = dao.transactions

        assertEquals(2, store.deleteClosed(keep = listOf("kept"), index = IndexUpdate.Update(mapOf("0" to byteArrayOf(2)), emptySet())))

        assertEquals(setOf("kept", "open"), dao.rows.keys)
        assertArrayEquals(byteArrayOf(2), (store.shards().single() as StoredShard.Readable).payload)
        assertArrayEquals(byteArrayOf(2), (store.read(listOf("kept")).single() as StoredConversation.Readable).payload)
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
        var alsoDeletedWithTheRows = false

        assertEquals(KeyErasure.ERASED, store.deleteAll(alsoDeleting = { alsoDeletedWithTheRows = "a" in dao.rows && key.erased == 0 }))

        assertTrue(alsoDeletedWithTheRows)
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
        val next = ConversationArchiveStore(dao, key.inTheNextProcess(), dispatcher = Dispatchers.Unconfined)

        assertTrue(next.read(listOf("a")).isEmpty())
        assertEquals(0, key.erased)
        next.write(ConversationChange(listOf(PlainConversation(closed("b", last = 2), "new".encodeToByteArray())), emptyList(), IndexUpdate.Keep))
        assertEquals(1, key.erased)
        next.write(ConversationChange(listOf(PlainConversation(closed("c", last = 3), "more".encodeToByteArray())), emptyList(), IndexUpdate.Keep))
        next.purge(oldest = 0, newest = 10, IndexUpdate.Keep)
        assertEquals(1, key.erased)
        assertEquals(2, next.read(listOf("b", "c")).filterIsInstance<StoredConversation.Readable>().size)

        // An archive that holds rows keeps its key at a process's first change.
        ConversationArchiveStore(dao, key.inTheNextProcess(), dispatcher = Dispatchers.Unconfined).write(ConversationChange(emptyList(), listOf("c"), IndexUpdate.Keep))
        assertEquals(1, key.erased)
        assertTrue(next.read(listOf("b")).single() is StoredConversation.Readable)
    }

    // A "delete history" whose erasure failed deleted the rows and left the key: the deletion is complete, and deleteAll says
    // that the key was kept (design 9.7 of sub-project 7). The key must not encrypt the next conversation: the next change of
    // the same process checks the archive again and erases the key before it stores anything.
    @Test
    fun theNextChangeErasesAKeyThatDeleteAllFailedToErase() = runTest {
        write(PlainConversation(closed("a", last = 1), "words".encodeToByteArray()))
        key.failingErasures = 1

        assertEquals(KeyErasure.KEPT, store.deleteAll())
        assertTrue(dao.rows.isEmpty())
        assertTrue(key.exists)
        assertEquals(0, key.erased)

        write(PlainConversation(closed("b", last = 2), "new".encodeToByteArray()))

        assertEquals(1, key.erased)
        assertEquals(2, key.made)
        assertTrue(store.read(listOf("b")).single() is StoredConversation.Readable)
    }

    // The key and what this process knows of it are one per process (KeystoreErasableKey.of): a second archive object
    // of the same key, which has stored before, erases the key that the first one's "delete history" kept before it
    // stores again.
    @Test
    fun aSecondArchiveOfTheKeyErasesTheKeyThatTheFirstOnesDeleteAllKept() = runTest {
        val other = ConversationArchiveStore(dao, key, dispatcher = Dispatchers.Unconfined)
        other.write(ConversationChange(listOf(PlainConversation(closed("a", last = 1), "words".encodeToByteArray())), emptyList(), IndexUpdate.Keep))
        key.failingErasures = 1

        assertEquals(KeyErasure.KEPT, store.deleteAll())
        other.write(ConversationChange(listOf(PlainConversation(closed("b", last = 2), "new".encodeToByteArray())), emptyList(), IndexUpdate.Keep))

        assertEquals(1, key.erased)
        assertEquals(2, key.made)
        assertTrue(store.read(listOf("b")).single() is StoredConversation.Readable)
    }

    // A "delete history" whose transaction failed deleted nothing: it throws, and every row, the index and the key
    // stay, also when it failed after what else it deletes (the logs, in PrivacyStore's) and before the rows.
    @Test
    fun aDeleteAllWhoseTransactionFailsThrowsAndKeepsEveryRowAndTheKey() = runTest {
        write(PlainConversation(closed("a", last = 1), "words".encodeToByteArray()), index = IndexUpdate.Update(mapOf("0" to byteArrayOf(1)), emptySet()))

        assertTrue(runCatching { store.deleteAll(alsoDeleting = { throw IOException("the disk failed") }) }.exceptionOrNull() is IOException)

        assertEquals(setOf("a"), dao.rows.keys)
        assertEquals(setOf("0"), dao.shards.keys)
        assertEquals(0, key.erased)
        assertTrue(store.read(listOf("a")).single() is StoredConversation.Readable)
    }

    // Design 9.7 of sub-project 7: deleteAll throws only when its transaction did not commit, also for a caller that is
    // cancelled while the transaction runs: the transaction and its result are one unit that cancellation cannot split.
    @Test
    fun aCallerCancelledWhileTheTransactionRunsStillGetsItsResult() = runTest {
        write(PlainConversation(closed("a", last = 1), byteArrayOf(1)))
        val inside = CountDownLatch(1)
        val gate = CountDownLatch(1)
        dao.beforeDeleteAll = { inside.countDown(); gate.await(5, TimeUnit.SECONDS) }
        var outcome: Result<KeyErasure>? = null
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val store = ConversationArchiveStore(dao, key, dispatcher = dispatcher)
            val delete = launch(Dispatchers.Default) { outcome = runCatching { store.deleteAll() } }
            assertTrue(inside.await(5, TimeUnit.SECONDS))

            delete.cancel()
            gate.countDown()
            delete.join()
        }

        assertEquals(KeyErasure.ERASED, outcome?.getOrThrow())
        assertTrue(dao.rows.isEmpty())
        assertEquals(1, key.erased)
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
        val watched = object : ErasableKey() {
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

    // A large row is read again with its pieces in one transaction, so that its header and its payload come from the
    // version of the row that the transaction sees: an open row that closes between the first query and the pieces is
    // not given as the open conversation.
    @Test
    fun aLargeOpenRowThatClosesWhileItIsReadIsNotTheOpenConversation() = runTest {
        val payload = ByteArray(ConversationDao.PIECE_BYTES + 1) { it.toByte() }
        write(PlainConversation(opened("a", last = 1), payload))
        val row = dao.rows.getValue("a")
        dao.afterQuery = {
            dao.afterQuery = {}
            dao.rows["a"] = ConversationRow("a", row.openedMillis, row.lastMillis, open = false, commands = row.commands, payload = row.payload)
        }

        assertEquals(null, store.open())
        assertEquals(listOf("a" to false), store.read(listOf("a")).map { it.header.id to it.header.open })
    }

    // Design R3 of sub-project 7: a Keystore operation right after a hop to another thread costs about twice as much,
    // so each read goes to the store's dispatcher once, for its query, its pieces and its decryptions, also for a
    // payload or a shard that is read in pieces. A second dispatch between them would count here, whichever thread it
    // lands on.
    @Test
    fun eachReadDispatchesOnceForItsQueryItsPiecesAndItsDecryptions() = runTest {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { executor ->
            val dispatcher = CountingDispatcher(executor)
            val store = ConversationArchiveStore(dao, key, dispatcher = dispatcher)
            val large = ByteArray(ConversationDao.PIECE_BYTES + 1) { it.toByte() }
            store.write(ConversationChange(listOf(PlainConversation(opened("a", last = 1), large), PlainConversation(closed("b", last = 1), byteArrayOf(1))), emptyList(), IndexUpdate.Update(mapOf("0" to large, "1" to byteArrayOf(2)), emptySet())))
            dispatcher.dispatches.set(0)

            store.open()
            assertEquals(1, dispatcher.dispatches.get())
            store.shards()
            assertEquals(2, dispatcher.dispatches.get())
            store.read(listOf("a", "b"))
            assertEquals(3, dispatcher.dispatches.get())
        }
    }

    // A row that is larger than Android's cursor window (2 MB) cannot be read in one query, so the archive reads a
    // payload or a shard larger than a piece in pieces, each within the window, and assembles it before it decrypts it.
    @Test
    fun aPayloadOrAShardLargerThanAPieceIsReadInPiecesAndAssembled() = runTest {
        val payload = ByteArray(2 * ConversationDao.PIECE_BYTES + 7) { (it % 251).toByte() }
        val shard = ByteArray(ConversationDao.PIECE_BYTES + 3) { (it % 13).toByte() }
        write(PlainConversation(opened("a", last = 2), payload), PlainConversation(closed("b", last = 1), payload), index = IndexUpdate.Update(mapOf("0" to shard), emptySet()))

        assertArrayEquals(payload, (store.open() as StoredConversation.Readable).payload)
        assertArrayEquals(payload, (store.read(listOf("b")).single() as StoredConversation.Readable).payload)
        assertArrayEquals(shard, (store.shards().single() as StoredShard.Readable).payload)
        // A payload of 2 pieces and 36 bytes (7 and the cipher's 29) takes 3 pieces, twice; the shard's 2 pieces.
        assertEquals(8, dao.pieces)
    }

    // Design 9.7 of sub-project 7: a read never makes the key. A read without the key (none was made, or "delete
    // history" erased it while the read decrypted) finds every row failing its check, no readable shard, and a later
    // format still unknown.
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
 * What Keystore holds for one alias, which every process's key object of it shares: the key, made when there is none,
 * and deleted by an erasure; [erased] counts the erasures of a key that existed, so that a first change that finds no
 * key to erase does not count. The next [failingErasures] erasures throw, as Keystore may, and keep the key.
 */
private class MemoryKeystore {
    var key: SecretKey? = null
    var failingErasures = 0
    var erased = 0
    var made = 0
}

/** An [ErasableKey] in memory: this process's key object of [keystore]'s entry. */
private class MemoryKey(private val keystore: MemoryKeystore = MemoryKeystore()) : ErasableKey() {
    var failingErasures: Int
        get() = synchronized(keystore) { keystore.failingErasures }
        set(value) = synchronized(keystore) { keystore.failingErasures = value }

    val erased: Int get() = synchronized(keystore) { keystore.erased }

    val made: Int get() = synchronized(keystore) { keystore.made }

    val exists: Boolean get() = synchronized(keystore) { keystore.key != null }

    /** The key object of the same Keystore entry in the next process, which knows nothing of this one's checks. */
    fun inTheNextProcess() = MemoryKey(keystore)

    override fun get(): SecretKey = synchronized(keystore) {
        keystore.key ?: KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also {
            keystore.key = it
            keystore.made++
        }
    }

    override fun <T> withExisting(block: (SecretKey?) -> T): T = synchronized(keystore) { block(keystore.key) }

    override fun erase() = synchronized(keystore) {
        if (keystore.failingErasures > 0) {
            keystore.failingErasures--
            throw KeyStoreException("the key could not be deleted")
        }
        if (keystore.key != null) keystore.erased++
        keystore.key = null
    }
}

/** A dispatcher that counts how often a coroutine is sent to it. */
private class CountingDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
    val dispatches = AtomicInteger()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatches.incrementAndGet()
        delegate.dispatch(context, block)
    }
}

/**
 * Mimics the SQL in [ConversationDao]; KestrelDatabaseTest checks the real queries and the transactions on a device.
 * Its write, purge, deleteAll and the assembly of a large payload are the DAO's own, so these tests run them;
 * [transactions] counts the writes and the purges, and [pieces] the pieces that were read.
 */
private class FakeConversationDao : ConversationDao() {
    val rows = linkedMapOf<String, ConversationRow>()
    val shards = linkedMapOf<String, ConversationIndexRow>()
    var beforeUpsert: () -> Unit = {}
    var beforeDeleteAll: () -> Unit = {}
    var transactions = 0
    var pieces = 0

    // Runs after each query of rows, as a write between that query and a later one would.
    var afterQuery: () -> Unit = {}

    override fun write(rows: List<ConversationRow>, deleted: List<String>, index: IndexRowUpdate) {
        transactions++
        super.write(rows, deleted, index)
    }

    override fun purge(oldest: Long, newest: Long, index: IndexRowUpdate): Int {
        transactions++
        return super.purge(oldest, newest, index)
    }

    override fun deleteClosed(keep: List<String>, index: IndexRowUpdate): Int {
        transactions++
        return super.deleteClosed(keep, index)
    }

    override fun deleteAll(alsoDeleting: () -> Unit) {
        beforeDeleteAll()
        super.deleteAll(alsoDeleting)
    }

    override fun openRead(): ConversationRowRead? = rows.values.firstOrNull { it.open }?.let(::read).also { afterQuery() }

    override fun rowReads(ids: List<String>): List<ConversationRowRead> = ids.mapNotNull { rows[it] }.map(::read).also { afterQuery() }

    override fun closedIds(): List<String> = rows.values.filter { !it.open }.sortedBy { it.lastMillis }.map { it.id }

    override fun shardReads(): List<IndexRowRead> =
        shards.values.map { IndexRowRead(it.name, it.payload.size, it.payload.takeIf { p -> p.size <= PIECE_BYTES }) }.also { afterQuery() }

    override fun isEmpty(): Boolean = rows.isEmpty() && shards.isEmpty()

    override fun stamp(): ConversationStamp {
        val closed = rows.values.filter { !it.open }.map { it.lastMillis }
        return ConversationStamp(closed.size, closed.maxOrNull() ?: 0, closed.sum())
    }

    override fun conversationPiece(id: String, from: Int, count: Int): ByteArray? = rows[id]?.payload?.let { piece(it, from, count) }

    override fun shardPiece(name: String, from: Int, count: Int): ByteArray? = shards[name]?.payload?.let { piece(it, from, count) }

    // SQLite's substr on a blob: [count] bytes from the 1-based [from].
    private fun piece(payload: ByteArray, from: Int, count: Int): ByteArray {
        pieces++
        return payload.copyOfRange(from - 1, minOf(payload.size, from - 1 + count))
    }

    private fun read(row: ConversationRow) =
        ConversationRowRead(row.id, row.openedMillis, row.lastMillis, row.open, row.commands, row.payload.size, row.payload.takeIf { it.size <= PIECE_BYTES })

    override fun delete(id: String): Int = if (rows.remove(id) != null) 1 else 0

    override fun deleteClosedExcept(keep: List<String>): Int {
        val gone = rows.values.filter { !it.open && it.id !in keep }.map { it.id }
        gone.forEach { rows.remove(it) }
        return gone.size
    }

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
