package dev.kestrel.screen.act

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GestureRunnerTest {
    @Test
    fun aCancelledGestureEndsOnlyOnceThePartThatAndroidStartedHasEndedAndStartsNoOther() = runTest {
        val started = mutableListOf<String>()
        val job = launch {
            GestureRunner.inOrder(listOf("hold", "move"), started = { started += it }) {
                // Android answers when the part's 800 ms have passed.
                delay(800)
                GestureOutcome.COMPLETED
            }
        }
        advanceTimeBy(100)
        job.cancel()
        runCurrent()

        // Android still runs the hold, so the job that the next command waits for has not completed.
        assertFalse(job.isCompleted)
        advanceTimeBy(800)
        runCurrent()
        assertTrue(job.isCancelled && job.isCompleted)
        assertEquals(listOf("hold"), started)
    }
}
