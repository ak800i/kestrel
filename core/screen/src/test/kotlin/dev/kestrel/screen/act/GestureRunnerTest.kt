package dev.kestrel.screen.act

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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

    // Re-check of round 12, Minor A: the press learns the moment that its gesture goes to Android, and learns too when
    // Android refused it, so that only a gesture that Android took keeps what the press did then.
    @Test
    fun aHandedOverGestureTellsThePressRightBeforeAndAgainWhenAndroidRefusedIt() {
        val told = mutableListOf<String>()
        val press = object : PressDispatch {
            override fun dispatching() {
                told += "dispatching"
            }

            override fun notDispatched() {
                told += "not dispatched"
            }
        }

        assertTrue(GestureRunner.handedOver(press) { told += "Android"; true })
        assertEquals(listOf("dispatching", "Android"), told)
        told.clear()
        assertFalse(GestureRunner.handedOver(press) { told += "Android"; false })
        assertEquals(listOf("dispatching", "Android", "not dispatched"), told)
        assertTrue(GestureRunner.handedOver(null) { true })
    }

    // Re-check of round 13, Minor 1: a hand-over that throws reached Android no more than a refused one, so the press learns
    // that too, and the throw goes on to the caller.
    @Test
    fun aHandOverThatThrowsTellsThePressItWasNotDispatchedAndThrowsOn() {
        val told = mutableListOf<String>()
        val press = object : PressDispatch {
            override fun dispatching() {
                told += "dispatching"
            }

            override fun notDispatched() {
                told += "not dispatched"
            }
        }
        val failure = IllegalArgumentException("the gesture's steps")

        val thrown = runCatching { GestureRunner.handedOver(press) { throw failure } }.exceptionOrNull()

        assertSame(failure, thrown)
        assertEquals(listOf("dispatching", "not dispatched"), told)
    }
}
