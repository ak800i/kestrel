package dev.kestrel.app.listen

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The screen's turn (spec 7.3; design 7.1 of sub-project 7; plan 7D). */
@OptIn(ExperimentalCoroutinesApi::class)
class ScreenTurnsTest {
    private val reports = mutableListOf<String>()
    private val turns = ScreenTurns({ what, _ -> reports += what })

    @Test
    fun withoutACancelledCommandTheScreenIsFreeAtOnce() = runTest {
        assertFalse(turns.held())

        turns.take()

        assertEquals(0L, currentTime)
    }

    @Test
    fun aCancelledCommandHoldsTheScreenUntilItsJobHasCompleted() = runTest {
        val job = Job()
        turns.stopped(job)
        assertTrue(turns.held())

        val taken = async { turns.take() }
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(taken.isCompleted)
        job.complete()
        runCurrent()

        assertTrue(taken.isCompleted)
        assertFalse(turns.held())
        assertEquals(emptyList<String>(), reports)
    }

    @Test
    fun aStuckCommandHoldsTheScreenForAtMostThreeSecondsAndIsReportedOnce() = runTest {
        turns.stopped(Job())

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
        turns.take()
        assertEquals(1, reports.size)
    }
}
