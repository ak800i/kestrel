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

        assertFalse(turns.take())

        assertEquals(0L, currentTime)
    }

    // Whole-plan re-review of plan 7D, N1: a command that still runs holds the screen too. Whoever takes the turn asks it to
    // let go, and gets the screen only once its job has really ended.
    @Test
    fun aRunningCommandIsAskedToLetGoAndHoldsTheScreenUntilItsJobHasEnded() = runTest {
        val job = Job()
        var asked = 0
        turns.started(job) { asked++ }
        assertTrue(turns.held())

        val taken = async { turns.take() }
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
        turns.started(job) { job.cancel() }

        val taken = async { turns.take() }
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

    @Test
    fun aStuckCommandHoldsTheScreenForAtMostThreeSecondsAndIsReportedOnce() = runTest {
        turns.started(Job()) {}

        val taken = async { turns.take() }
        advanceTimeBy(2_999)
        runCurrent()
        assertFalse(taken.isCompleted)
        advanceTimeBy(2)
        runCurrent()

        assertTrue(taken.isCompleted)
        assertEquals(listOf("A cancelled instant command"), reports)
        // Kestrel goes on without it: nothing waits for it again.
        assertFalse(turns.held())
        assertFalse(turns.take())
        assertEquals(1, reports.size)
    }
}
