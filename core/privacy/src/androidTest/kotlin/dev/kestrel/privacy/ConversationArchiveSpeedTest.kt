package dev.kestrel.privacy

import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.privacy.conversations.ConversationArchiveStore
import dev.kestrel.privacy.conversations.PlainConversation
import dev.kestrel.privacy.conversations.StoredConversation
import dev.kestrel.privacy.crypto.KeystoreErasableKey
import dev.kestrel.privacy.db.ConversationHeader
import dev.kestrel.privacy.db.KestrelDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How long the first read of 30 days of past conversations takes (design 4.3 and R3 of sub-project 7): 300 closed
 * conversations of 12 KB each are written to a database file and read back as plan 7B's store reads them at its first use
 * (every header, then each row), through a new store, as after a start of Kestrel, so that each row is decrypted once
 * through the Keystore. It is a measurement: it fails only when a conversation does not read back, and it logs the time
 * under the tag KestrelArchive. Plan 7C's Task 8 decides on the A71's time. It runs only with the instrumentation argument
 * kestrelArchiveSpeed=true. It uses a test-named database file and a test key of this library's own test app, never
 * Kestrel's.
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
    fun measuresTheFirstReadOf30DaysOfConversations() = runBlocking {
        assumeTrue(
            "Runs with -Pandroid.testInstrumentationRunnerArguments.kestrelArchiveSpeed=true",
            InstrumentationRegistry.getArguments().getString("kestrelArchiveSpeed") == "true",
        )
        val payload = ByteArray(PAYLOAD_BYTES) { ('a'.code + it % 26).toByte() }
        val now = System.currentTimeMillis()
        val written = database()
        try {
            val writer = ConversationArchiveStore(written.conversationDao(), KeystoreErasableKey.of(alias))
            writer.write(
                List(CONVERSATIONS) { n ->
                    val last = now - DAYS * DAY_MILLIS * n / CONVERSATIONS
                    PlainConversation(ConversationHeader("test-$n", last - 60_000, last, open = false, commands = 3), payload)
                },
            )
        } finally {
            written.close()
        }

        val read = database()
        val (millis, readable) = try {
            val reader = ConversationArchiveStore(read.conversationDao(), KeystoreErasableKey.of(alias))
            val started = SystemClock.elapsedRealtime()
            val conversations = reader.headers().map { reader.read(it.id) }
            (SystemClock.elapsedRealtime() - started) to conversations.count { it is StoredConversation.Readable }
        } finally {
            read.close()
        }

        Log.i(TAG, "read $readable conversations of ${PAYLOAD_BYTES / 1024} KB in $millis ms")
        assertEquals(CONVERSATIONS, readable)
    }

    private fun database() = Room.databaseBuilder(context, KestrelDatabase::class.java, FILE).build()

    private companion object {
        const val TAG = "KestrelArchive"
        const val FILE = "test-archive-speed.db"
        const val CONVERSATIONS = 300
        const val PAYLOAD_BYTES = 12 * 1024
        const val DAYS = 30L
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
