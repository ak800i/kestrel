package dev.kestrel.screen.read

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SnapshotCacheTest {
    @Test
    fun reusesTheSnapshotUntilTheScreenChangesAndThenWaitsForAQuietScreen() = runTest {
        val tracker = ScreenChangeTracker { testScheduler.currentTime }
        var builds = 0
        val cache = SnapshotCache(tracker) { ++builds }

        assertEquals(1, cache.current())
        assertEquals(1, cache.current())
        val changedAt = testScheduler.currentTime
        tracker.onScreenChanged()

        assertEquals(2, cache.current())
        assertTrue(testScheduler.currentTime - changedAt >= ScreenChangeTracker.SNAPSHOT_QUIET_MILLIS)
    }

    @Test
    fun aChangeDuringABuildMakesTheNextCallBuildAgain() = runTest {
        val tracker = ScreenChangeTracker { testScheduler.currentTime }
        var builds = 0
        val cache = SnapshotCache(tracker) {
            builds++
            if (builds == 1) tracker.onScreenChanged()
            builds
        }

        assertEquals(1, cache.current())
        assertEquals(2, cache.current())
    }

    @Test
    fun anInvalidateDuringABuildIsNotLost() = runTest {
        val tracker = ScreenChangeTracker { testScheduler.currentTime }
        var builds = 0
        lateinit var cache: SnapshotCache<Int>
        cache = SnapshotCache(tracker) {
            builds++
            // Another caller invalidates the cache while this snapshot is being built.
            if (builds == 1) cache.invalidate()
            builds
        }

        assertEquals(1, cache.current())
        assertEquals(2, cache.current())
    }

    @Test
    fun callersOnABusyScreenWaitForQuietSideBySide() = runTest {
        val tracker = ScreenChangeTracker { testScheduler.currentTime }
        var builds = 0
        val cache = SnapshotCache(tracker) {
            delay(100)
            ++builds
        }
        cache.current()
        // From now on the screen changes every 50 ms, as a stopwatch would.
        val ticking = launch {
            while (true) {
                tracker.onScreenChanged()
                delay(50)
            }
        }
        val started = testScheduler.currentTime

        val first = async { cache.current() }
        val second = async { cache.current() }
        first.await()
        second.await()
        ticking.cancel()

        // Each caller waits at most 3 s for quiet and then builds; the second does not wait 3 s more after the first.
        assertTrue(testScheduler.currentTime - started < 2 * ScreenChangeTracker.MAX_WAIT_MILLIS)
    }

    @Test
    fun invalidateMakesTheNextCallBuildAgain() = runTest {
        val tracker = ScreenChangeTracker { testScheduler.currentTime }
        var builds = 0
        val cache = SnapshotCache(tracker) { ++builds }
        cache.current()

        cache.invalidate()

        assertEquals(2, cache.current())
    }
}
