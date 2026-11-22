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

@OptIn(ExperimentalCoroutinesApi::class)
class AppAgentEnvironmentTest {
    @Test
    fun aTaskThatIsCancelledWhileTheWatcherStartsStopsTheWatcherAgain() = runTest {
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
