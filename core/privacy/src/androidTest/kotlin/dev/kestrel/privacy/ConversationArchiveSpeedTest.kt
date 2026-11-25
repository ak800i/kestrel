package dev.kestrel.privacy

import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.privacy.conversations.ConversationArchiveStore
import dev.kestrel.privacy.conversations.ConversationChange
import dev.kestrel.privacy.conversations.IndexUpdate
import dev.kestrel.privacy.conversations.PlainConversation
import dev.kestrel.privacy.conversations.StoredConversation
import dev.kestrel.privacy.conversations.StoredShard
import dev.kestrel.privacy.crypto.KeystoreErasableKey
import dev.kestrel.privacy.db.ConversationHeader
import dev.kestrel.privacy.db.KestrelDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How long the archive's reads and a close of 30 days of past conversations take (design 4.3 and R3 of sub-project 7): 300
 * closed conversations of 12 KB, an open one and an index in two shards of 240 and 210 KB, the size that plan 7B's test
 * fixture of 30 heavy days makes, are written to a database file, and read back through a new store each time, as after a
 * start of Kestrel: the open conversation (a restore), the index's shards and the stamp, the bodies of a search's 5 best matches in
 * one call, every closed conversation in pages of 50, as a rebuild of the index reads them, and one close, which writes a
 * conversation and its shard again. It is a measurement: it fails only when something does not read back, and it logs the
 * times under the tag KestrelArchive. Plan 7C's Task 8 decides on the A71's times. It runs only with the instrumentation
 * argument kestrelArchiveSpeed=true. It uses a test-named database file and a test key of this library's own test app,
 * never Kestrel's.
 */
@RunWith(AndroidJUnit4::class)
class ConversationArchiveSpeedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val alias = "kestrel.test.archive-speed.${System.nanoTime()}"

    @After
    fun cleanUp() {
        context.deleteDatabase(FILE)
        KeystoreErasableKey.of(alias).erase()
    }

    @Test
    fun measuresTheReadsAndACloseOf30DaysOfConversations() = runBlocking {
        assumeTrue(
            "Runs with -Pandroid.testInstrumentationRunnerArguments.kestrelArchiveSpeed=true",
            InstrumentationRegistry.getArguments().getString("kestrelArchiveSpeed") == "true",
        )
        val payload = ByteArray(PAYLOAD_BYTES) { ('a'.code + it % 26).toByte() }
        val now = System.currentTimeMillis()
        val conversations = List(CONVERSATIONS) { n ->
            val last = now - DAYS * DAY_MILLIS * (n + 1) / CONVERSATIONS
            PlainConversation(ConversationHeader("test-$n", last - 60_000, last, open = false, commands = 3), payload)
        } + PlainConversation(ConversationHeader("test-open", now - 60_000, now, open = true, commands = 3), payload)
        val shards = SHARD_BYTES.withIndex().associate { (n, size) -> n.toString() to ByteArray(size) { (it % 251).toByte() } }
        timed { it.write(ConversationChange(conversations, emptyList(), IndexUpdate.Replace(shards))) }

        val (open, openMillis) = timed { it.open() }
        val (index, indexMillis) = timed { store -> store.open(); store.shards() to store.stamp() }
        val (bodies, bodiesMillis) = timed { store -> store.open(); store.shards(); store.stamp(); store.read(List(MATCHES) { "test-${it * 7}" }) }
        val (rebuilt, rebuildMillis) = timed { store -> store.closedIds().chunked(PAGE).sumOf { page -> store.read(page).count { it is StoredConversation.Readable } } }
        val closing = PlainConversation(ConversationHeader("test-open", now - 60_000, now + 1, open = false, commands = 3), payload)
        val (_, closeMillis) = timed { it.write(ConversationChange(listOf(closing), emptyList(), IndexUpdate.Update(mapOf("1" to shards.getValue("1")), emptySet()))) }

        Log.i(TAG, "restore: the open conversation of ${PAYLOAD_BYTES / 1024} KB in $openMillis ms")
        Log.i(TAG, "restore and the index in ${SHARD_BYTES.size} shards of ${SHARD_BYTES.map { it / 1024 }} KB in $indexMillis ms")
        Log.i(TAG, "restore, the index and then $MATCHES conversations of ${PAYLOAD_BYTES / 1024} KB in $bodiesMillis ms")
        Log.i(TAG, "a rebuild's read of $rebuilt conversations of ${PAYLOAD_BYTES / 1024} KB in pages of $PAGE in $rebuildMillis ms")
        Log.i(TAG, "a close with a shard of ${SHARD_BYTES[1] / 1024} KB in $closeMillis ms")
        assertTrue(open is StoredConversation.Readable)
        assertEquals(SHARD_BYTES, index.first.sortedBy { it.name }.map { (it as StoredShard.Readable).payload.size })
        assertEquals(CONVERSATIONS, index.second.closed)
        assertEquals(MATCHES, bodies.count { it is StoredConversation.Readable })
        assertEquals(CONVERSATIONS, rebuilt)
    }

    /** [block]'s result and its time in ms, on a new database instance and a new store, as after a start of Kestrel. */
    private suspend fun <T> timed(block: suspend (ConversationArchiveStore) -> T): Pair<T, Long> {
        val database = Room.databaseBuilder(context, KestrelDatabase::class.java, FILE).build()
        try {
            val store = ConversationArchiveStore(database.conversationDao(), KeystoreErasableKey.of(alias))
            val started = SystemClock.elapsedRealtime()
            val result = block(store)
            return result to SystemClock.elapsedRealtime() - started
        } finally {
            database.close()
        }
    }

    private companion object {
        const val TAG = "KestrelArchive"
        const val FILE = "test-archive-speed.db"
        const val CONVERSATIONS = 300
        const val PAYLOAD_BYTES = 12 * 1024
        val SHARD_BYTES = listOf(240 * 1024, 210 * 1024)
        const val PAGE = 50
        const val MATCHES = 5
        const val DAYS = 30L
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
