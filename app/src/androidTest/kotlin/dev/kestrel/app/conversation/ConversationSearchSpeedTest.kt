package dev.kestrel.app.conversation

import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.agent.EndReason
import dev.kestrel.agent.goal.HistoryPort
import dev.kestrel.agent.goal.HistoryQuery
import dev.kestrel.agent.goal.HistoryResult
import dev.kestrel.conversation.model.AppUse
import dev.kestrel.conversation.model.CommandRecord
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.ConversationSnapshot
import dev.kestrel.conversation.model.DataNote
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.LineKind
import dev.kestrel.conversation.model.RecordLine
import dev.kestrel.conversation.store.ArchivedConversationStore
import dev.kestrel.conversation.store.HistorySearch
import dev.kestrel.privacy.conversations.ConversationArchiveStore
import dev.kestrel.privacy.crypto.KeystoreErasableKey
import dev.kestrel.privacy.db.KestrelDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * How long the first search over 30 days of past conversations takes, a close, and a search right after a start (design
 * 4.3 and R3 of sub-project 7; plan 7E), in three instrumentation runs, each a new process, with design R3's fixture:
 * 300 closed conversations of two commands, each command with about 300 words drawn evenly from 25,000 made-up words with
 * a fixed seed, so that the index of past conversations has the size of 30 heavy days (about 450 KB in two shards).
 * - kestrelSearchSpeed=write saves them through plan 7B's store into plan 7C's archive in a test database with a test key,
 *   oldest first, so that each close encodes and encrypts the index's newest shard again, times the last close, and leaves
 *   an open conversation of today.
 * - kestrelSearchSpeed=search lets a new store read the index in the background and times the first search after it.
 * - kestrelSearchSpeed=start times a search right after a new store's start, which waits for the index while the store
 *   reads it; the restore reads only the open conversation. Afterwards the run deletes the database and erases the key.
 * It is a measurement: it fails only when the store could not use the index or the search does not find the
 * conversations that hold the words, and it logs under the tag KestrelSearch. It never uses Kestrel's own database or key.
 */
@RunWith(AndroidJUnit4::class)
class ConversationSearchSpeedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val random = Random(SEED)
    private val vocabulary: List<String> by lazy { generateSequence { word() }.distinct().take(VOCABULARY).toList() }

    @Test
    fun measuresTheFirstSearchOver30DaysOfConversations() = runBlocking<Unit> {
        val mode = InstrumentationRegistry.getArguments().getString("kestrelSearchSpeed")
        assumeTrue("Runs with -Pandroid.testInstrumentationRunnerArguments.kestrelSearchSpeed=write, then =search, then =start", mode != null && mode in MODES)
        when (mode) {
            "write" -> write()
            "search" -> search()
            else -> start()
        }
    }

    private suspend fun CoroutineScope.write() {
        context.deleteDatabase(FILE)
        KeystoreErasableKey.of(ALIAS).erase()
        val now = System.currentTimeMillis()
        val written = database()
        try {
            val store = store(written, this)
            // The store has read the empty archive, so that each close writes the index's newest shard at once.
            assertTrue(store.list().isEmpty())
            val started = SystemClock.elapsedRealtime()
            for (n in CONVERSATIONS - 1 downTo 1) store.save(conversation(n, now - DAYS * DAY_MILLIS * n / CONVERSATIONS))
            val newest = conversation(0, now - 60_000L)
            store.save(newest.copy(open = true))
            val closing = SystemClock.elapsedRealtime()
            store.save(newest)
            val closed = SystemClock.elapsedRealtime()
            val today = CommandRecord(1, now - 30_000L, InputChannel.VOICE, "Turn on dark mode", answer = "Dark mode is on.", end = EndReason.FINISHED)
            store.save(ConversationSnapshot(ConversationId.random(), now - 30_000L, now, open = true, testMode = true, records = listOf(today)))
            assertEquals(CONVERSATIONS, store.list().size)

            Log.i(TAG, "wrote $CONVERSATIONS conversations in ${(closed - started) / 1_000} s")
            Log.i(TAG, "closed a conversation in ${closed - closing} ms")
        } finally {
            written.close()
        }
    }

    private suspend fun CoroutineScope.search() {
        val read = database()
        try {
            val opened = SystemClock.elapsedRealtime()
            val store = store(read, this)
            // list() waits while the store reads the index in the background.
            assertEquals(CONVERSATIONS, store.list().size)
            val loaded = SystemClock.elapsedRealtime()
            val found = HistorySearch(store, { DAYS.toInt() }, AndroidConversationClock).search(HistoryQuery(QUERY))
            val searched = SystemClock.elapsedRealtime()

            Log.i(TAG, "read the index in ${loaded - opened} ms")
            Log.i(TAG, "searched $CONVERSATIONS conversations in ${searched - loaded} ms")
            assertFound(found)
        } finally {
            read.close()
        }
    }

    private suspend fun CoroutineScope.start() {
        val read = database()
        try {
            val started = SystemClock.elapsedRealtime()
            val store = store(read, this)
            val open = store.open()
            val restored = SystemClock.elapsedRealtime()
            // The search waits while the store reads the index in the background.
            val found = HistorySearch(store, { DAYS.toInt() }, AndroidConversationClock).search(HistoryQuery(QUERY))
            val searched = SystemClock.elapsedRealtime()

            Log.i(TAG, "restored the open conversation in ${restored - started} ms")
            Log.i(TAG, "searched right after a start in ${searched - started} ms")
            assertEquals("Turn on dark mode", open?.records?.single()?.request)
            assertFound(found)
        } finally {
            read.close()
            context.deleteDatabase(FILE)
            KeystoreErasableKey.of(ALIAS).erase()
        }
    }

    private fun assertFound(found: HistoryResult) {
        assertNull("The store could not use the index, so the search did not run", found.unavailable)
        assertEquals(HistoryPort.MAX_MATCHES, found.matches.size)
        assertTrue(found.more)
    }

    private fun database() = Room.databaseBuilder(context, KestrelDatabase::class.java, FILE).build()

    // The store's scope is the test's own, so that its reading of the index in the background ends before the run does. The
    // measurement deletes no history; the archive's "delete history" is the test table's own, as in RoomRecordArchiveTest.
    private fun store(database: KestrelDatabase, scope: CoroutineScope): ArchivedConversationStore {
        val table = ConversationArchiveStore(database.conversationDao(), KeystoreErasableKey.of(ALIAS))
        return ArchivedConversationStore(RoomRecordArchive(table) { table.deleteAll() }, scope) {}
    }

    // Two commands, each with about 300 words in its request, its lines, its answer and its data notes. The first command
    // of every tenth conversation names the restaurant that the search looks for.
    private fun conversation(n: Int, last: Long): ConversationSnapshot {
        val records = List(COMMANDS) { index ->
            val request = if (n % 10 == 0 && index == 0) "Find the restaurant Mila sent me last week" else "Check ${words(6)}"
            CommandRecord(
                number = index + 1,
                atWall = last - (COMMANDS - index) * 60_000L,
                channel = InputChannel.VOICE,
                request = request,
                lines = List(2) { RecordLine(LineKind.KESTREL_SAID, words(20)) },
                answer = words(30),
                end = EndReason.FINISHED,
                data = List(2) { DataNote("list_sms", it + 1, words(120), items = 10) },
                apps = listOf(AppUse("Viber", "com.viber.voip")),
                ledger = listOf("opened Viber"),
            )
        }
        return ConversationSnapshot(ConversationId.random(), last - 300_000L, last, open = false, testMode = true, records = records)
    }

    private fun words(count: Int): String = List(count) { vocabulary[random.nextInt(VOCABULARY)] }.joinToString(" ")

    // A made-up word of 4 to 10 letters that starts as words often do and then alternates vowels and consonants.
    private fun word(): String = buildString {
        append(FIRST[random.nextInt(FIRST.length)])
        repeat(3 + random.nextInt(7)) { append(if (it % 2 == 0) VOWELS[random.nextInt(VOWELS.length)] else CONSONANTS[random.nextInt(CONSONANTS.length)]) }
    }

    private companion object {
        const val TAG = "KestrelSearch"
        const val FILE = "test-search-speed.db"
        const val ALIAS = "kestrel.test.search-speed"
        const val QUERY = "restaurant Mila"
        const val CONVERSATIONS = 300
        const val COMMANDS = 2
        const val VOCABULARY = 25_000
        const val SEED = 25_112_026L
        const val DAYS = 30L
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
        const val FIRST = "ssscccppbbtttmmaddrrffhhgglwweiouvnkjy"
        const val VOWELS = "aeiouaeioy"
        const val CONSONANTS = "rstlnmdkpbgvzcfhj"
        val MODES = setOf("write", "search", "start")
    }
}
