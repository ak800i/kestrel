package dev.kestrel.app.listen

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The screen's turn, the one rule for who acts on the screen (spec 7.3; design 6.5 and 7.1 of sub-project 7; plan 7D). */
@OptIn(ExperimentalCoroutinesApi::class)
class ScreenTurnsTest {
    private val reports = mutableListOf<String>()
    private val turns = ScreenTurns({ what, _ -> reports += what })

    @Test
    fun withoutACommandTheScreenIsFreeAtOnce() = runTest {
        assertFalse(turns.held())

        assertFalse(turns.take(after = null) {})

        assertEquals(0L, currentTime)
    }

    // Re-review round 2, R2-1: a round that goes on by itself, or for an input of the owner's that came before the command,
    // never has the command cancelled; it waits as long as the command runs, because the command is his newest act.
    @Test
    fun aRoundWithoutANewerInputWaitsForTheRunningCommandWithoutCancellingIt() = runTest {
        for (after in listOf(null, 4L, 5L)) {
            val job = Job()
            var asked = 0
            turns.startCommand(job, since = 5) { asked++ }

            val taken = async { turns.take(after) {} }
            advanceTimeBy(60_000)
            runCurrent()
            assertFalse("$after", taken.isCompleted)
            assertEquals("$after", 0, asked)
            job.complete()
            runCurrent()

            assertTrue("$after", taken.await())
            assertFalse(turns.held())
            turns.release()
        }
        assertEquals(emptyList<String>(), reports)
    }

    // Plan 7E: a round that waits behind the owner's command, which goes on, says so as it starts to wait, so that the goal
    // notification can tell why the goal does not move while the bar belongs to the command. A round that has the command
    // cancelled, for the owner's newer input, waits only for the cancellation's end and says nothing.
    @Test
    fun aRoundSaysWhenItWaitsBehindTheOwnersCommandButNotWhenItHasItCancelled() = runTest {
        var waits = 0
        val job = Job()
        turns.startCommand(job, since = 5) {}

        val taken = async { turns.take(after = null) { waits++ } }
        runCurrent()
        assertEquals(1, waits)
        job.complete()
        assertTrue(taken.await())
        assertEquals(1, waits)
        turns.release()

        val cancelled = Job()
        turns.startCommand(cancelled, since = 5) { cancelled.complete() }
        assertTrue(turns.take(after = 6) { waits++ })
        assertEquals(1, waits)
    }

    // Re-review round 4, R4-1: while a round holds the turn, no instant command starts, whatever the Orchestrator reads of the
    // goal's phase; once the round lets go, as a wait for the owner starts or the round ends, commands start again.
    @Test
    fun aCommandCannotStartWhileARoundHoldsTheTurnAndCanAfterItLetsGo() = runTest {
        assertFalse(turns.take(after = null) {})
        assertTrue(turns.roundHolds())

        assertFalse(turns.startCommand(Job(), since = 5) {})
        assertFalse(turns.held())
        turns.release()

        assertFalse(turns.roundHolds())
        assertTrue(turns.startCommand(Job(), since = 5) {})
        assertTrue(turns.held())
    }

    // An input of the owner's that came after the command started chose the goal: the command is asked to let go, and the
    // round gets the screen once its job has really ended.
    @Test
    fun anOwnersInputAfterTheCommandStartedHasItCancelled() = runTest {
        val job = Job()
        var asked = 0
        turns.startCommand(job, since = 5) { asked++ }
        assertTrue(turns.held())

        val taken = async { turns.take(after = 6) {} }
        runCurrent()
        assertEquals(1, asked)
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(taken.isCompleted)
        job.complete()
        runCurrent()

        assertTrue(taken.await())
        assertFalse(turns.held())
        assertEquals(emptyList<String>(), reports)
    }

    @Test
    fun aCancelledCommandHoldsTheScreenUntilItsJobHasCompleted() = runTest {
        val finished = CompletableDeferred<Unit>()
        // Like a gesture or a binder call that Android finishes, the job ignores its cancellation for a while.
        val job = backgroundScope.launch { withContext(NonCancellable) { finished.await() } }
        runCurrent()
        turns.startCommand(job, since = 5) {}
        job.cancel()
        turns.stopped(job)

        val taken = async { turns.take(after = null) {} }
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(taken.isCompleted)
        assertTrue(turns.held())
        finished.complete(Unit)
        runCurrent()

        assertTrue(taken.await())
        assertFalse(turns.held())
        assertEquals(emptyList<String>(), reports)
    }

    // A round that waits for the owner's command gives it at most 3 s from the moment that it is cancelled, as "stop" does.
    @Test
    fun aWaitingRoundGivesACommandThatIsCancelledMeanwhileAtMostThreeSeconds() = runTest {
        val job = Job()
        turns.startCommand(job, since = 5) {}
        val taken = async { turns.take(after = null) {} }
        advanceTimeBy(10_000)
        runCurrent()
        assertFalse(taken.isCompleted)

        turns.stopped(job)
        advanceTimeBy(2_999)
        runCurrent()
        assertFalse(taken.isCompleted)
        advanceTimeBy(2)
        runCurrent()

        assertTrue(taken.await())
        assertEquals(listOf("A cancelled instant command"), reports)
    }

    @Test
    fun aStuckCommandHoldsTheScreenForAtMostThreeSecondsAndIsReportedOnce() = runTest {
        turns.startCommand(Job(), since = 5) {}

        val taken = async { turns.take(after = 6) {} }
        advanceTimeBy(2_999)
        runCurrent()
        assertFalse(taken.isCompleted)
        advanceTimeBy(2)
        runCurrent()

        assertTrue(taken.isCompleted)
        assertEquals(listOf("A cancelled instant command"), reports)
        // Kestrel goes on without it: nothing waits for it again.
        assertFalse(turns.held())
        assertFalse(turns.take(after = 7) {})
        assertEquals(1, reports.size)
    }
}
