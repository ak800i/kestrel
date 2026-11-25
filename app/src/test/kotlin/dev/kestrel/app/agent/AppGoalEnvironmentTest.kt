package dev.kestrel.app.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The touch watcher of a goal's round (design D19 of sub-project 5), which AppGoalEnvironment starts (plan 7D). */
@OptIn(ExperimentalCoroutinesApi::class)
class AppGoalEnvironmentTest {
    @Test
    fun aRoundThatIsCancelledWhileTheWatcherStartsStopsTheWatcherAgain() = runTest {
        var stopped = 0
        val adding = CompletableDeferred<Boolean>()
        val start = launch {
            startWatcherOrStop(start = { adding.await() }, stop = { delay(10); stopped++ })
        }
        runCurrent()

        start.cancel()
        runCurrent()
        testScheduler.advanceUntilIdle()

        assertTrue(start.isCancelled)
        assertEquals(1, stopped)
    }

    @Test
    fun aStartThatThrowsStopsTheWatcherAndRethrows() = runTest {
        var stopped = 0

        val thrown = runCatching {
            startWatcherOrStop(start = { throw IllegalStateException() }, stop = { stopped++ })
        }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertEquals(1, stopped)
    }
}
