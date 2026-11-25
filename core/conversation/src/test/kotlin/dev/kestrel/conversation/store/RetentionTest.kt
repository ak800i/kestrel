package dev.kestrel.conversation.store

import dev.kestrel.conversation.model.CommandRecord
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.ConversationSnapshot
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.testing.ManualConversationClock
import dev.kestrel.conversation.testing.MemoryArchive
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RetentionTest {
    @Test
    fun retentionPurgesAtOnceDailyAndWhenItShortens() = runTest {
        val day = ArchivedConversationStore.DAY_MILLIS
        val clock = ManualConversationClock({ testScheduler.currentTime })
        val store = ArchivedConversationStore(MemoryArchive(), backgroundScope) {}
        fun closed(n: Int, age: Long) = ConversationSnapshot(
            ConversationId(n.toString().padStart(32, '0')), clock.wallMillis() - age, clock.wallMillis() - age, open = false, testMode = true,
            records = listOf(CommandRecord(1, 0, InputChannel.VOICE, "Request $n")),
        )
        store.save(closed(1, 40 * day))
        store.save(closed(2, 10 * day))
        store.save(closed(3, 3 * day))
        val days = MutableStateFlow(30)
        backgroundScope.launch { Retention(store, clock) { _, _ -> }.keepPurging(days) }

        runCurrent()
        assertEquals(2, store.list().size)

        days.value = 7
        runCurrent()
        assertEquals(listOf(ConversationId("3".padStart(32, '0'))), store.list().map { it.id })

        advanceTimeBy(5 * day)
        runCurrent()
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun aDayOnTheWallClockCountsWhileThePhoneSleptAndAClockThatWentBackPurgesAgain() = runTest {
        val day = ArchivedConversationStore.DAY_MILLIS
        val hour = 60 * 60_000L
        val clock = ManualConversationClock({ testScheduler.currentTime })
        val store = ArchivedConversationStore(MemoryArchive(), backgroundScope) {}
        fun closed(n: Int, last: Long) = ConversationSnapshot(
            ConversationId(n.toString().padStart(32, '0')), last, last, open = false, testMode = true,
            records = listOf(CommandRecord(1, 0, InputChannel.VOICE, "Request $n")),
        )
        store.save(closed(1, clock.wallMillis() - 25 * day))
        backgroundScope.launch { Retention(store, clock) { _, _ -> }.keepPurging(MutableStateFlow(30)) }
        runCurrent()
        assertEquals(1, store.list().size)

        // The phone slept for six days: the wall clock moved on, the coroutines' clock did not.
        clock.base += 6 * day
        advanceTimeBy(hour)
        runCurrent()
        assertTrue(store.list().isEmpty())

        store.save(closed(2, clock.wallMillis()))
        clock.base -= 3 * day
        advanceTimeBy(hour)
        runCurrent()
        assertTrue(store.list().isEmpty())
    }
}
