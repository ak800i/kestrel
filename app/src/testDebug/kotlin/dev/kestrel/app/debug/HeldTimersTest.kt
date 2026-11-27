package dev.kestrel.app.debug

import dev.kestrel.conversation.engine.TimerEnd
import dev.kestrel.conversation.machine.Timer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The device tests' timer source that holds a conversation's timers (plan 7G). */
class HeldTimersTest {
    private val timers = HeldTimers(Timer.QUESTION)

    @Test
    fun aTimerOfAHeldKindWaitsUntilTheTestEndsIt() {
        val end = timers.start(Timer.QUESTION, 120_000) as TimerEnd.Held
        val held = checkNotNull(timers.newest(Timer.QUESTION))
        assertEquals(120_000L, held.millis)
        assertFalse(end.ended.isCompleted)

        held.end()

        assertTrue(end.ended.isCompleted)
        assertNull(timers.newest(Timer.QUESTION))
    }

    @Test
    fun aTimerOfAnotherKindRunsItsOwnLength() {
        assertEquals(TimerEnd.After(60_000), timers.start(Timer.QUIET, 60_000))
        assertNull(timers.newest(Timer.QUIET))
    }

    // The machine starts a question's timer again for a new question, and only the newest one ends the open question.
    @Test
    fun theNewestHeldTimerIsTheOneThatTheTestEnds() {
        val older = timers.start(Timer.QUESTION, 120_000) as TimerEnd.Held
        val newer = timers.start(Timer.QUESTION, 30_000) as TimerEnd.Held

        val held = checkNotNull(timers.newest(Timer.QUESTION))
        assertEquals(30_000L, held.millis)
        held.end()

        assertTrue(newer.ended.isCompleted)
        assertFalse(older.ended.isCompleted)
        assertSame(older.ended, checkNotNull(timers.newest(Timer.QUESTION)).ended)
    }

    @Test
    fun releaseEndsEveryTimerThatItStillHolds() {
        val first = timers.start(Timer.QUESTION, 120_000) as TimerEnd.Held
        val second = timers.start(Timer.QUESTION, 30_000) as TimerEnd.Held

        timers.release()

        assertTrue(first.ended.isCompleted)
        assertTrue(second.ended.isCompleted)
        assertNull(timers.newest(Timer.QUESTION))
    }
}
