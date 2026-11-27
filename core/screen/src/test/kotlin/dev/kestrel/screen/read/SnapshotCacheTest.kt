package dev.kestrel.screen.read

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        assertTrue(testScheduler.currentTime - started < 2 * QuietBudget.MILLIS)
    }

    @Test
    fun aCommandOnAScreenThatNeverGoesQuietWaitsForItAtMostItsBudgetInAll() = runTest {
        val tracker = ScreenChangeTracker { testScheduler.currentTime }
        var builds = 0
        val cache = SnapshotCache(tracker) {
            delay(BUILD_MILLIS)
            ++builds
        }
        // A change every 110 ms, as SystemUI's shade sends while it sets its date again and again.
        val ticking = launch {
            while (true) {
                tracker.onScreenChanged()
                delay(110)
            }
        }

        // A command in the shade: the snapshot that finds the element, the settle wait after the action, and the
        // snapshots that check what the action did.
        withQuietBudget {
            cache.current()
            tracker.awaitQuiet(ScreenChangeTracker.ACTION_QUIET_MILLIS, since = tracker.now())
            cache.current()
            cache.current()
        }
        ticking.cancel()

        assertEquals(3, builds)
        assertEquals(QuietBudget.MILLIS + builds * BUILD_MILLIS, testScheduler.currentTime)
    }

    @Test
    fun aCommandOnAScreenThatGoesQuietStillWaitsForItBeforeEachSnapshot() = runTest {
        val tracker = ScreenChangeTracker { testScheduler.currentTime }
        val builtAt = mutableListOf<Long>()
        val cache = SnapshotCache(tracker) { testScheduler.currentTime.also { builtAt += it } }

        withQuietBudget {
            // The screen changes for 250 ms after the command starts, and the snapshot waits for 150 ms of quiet after it.
            val changes = launch {
                repeat(6) {
                    tracker.onScreenChanged()
                    delay(50)
                }
            }
            cache.current()
            changes.join()
            // An action's change later on gets its own wait.
            tracker.onScreenChanged()
            cache.current()
        }

        assertEquals(listOf(400L, 550L), builtAt)
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

    @Test
    fun latestGivesTheNewestSnapshotWithoutWaitingOrBuilding() = runTest {
        val tracker = ScreenChangeTracker { testScheduler.currentTime }
        var builds = 0
        val cache = SnapshotCache(tracker) { ++builds }
        assertNull(cache.latest())
        cache.current()
        val changedAt = testScheduler.currentTime
        tracker.onScreenChanged()

        // The screen has changed, but latest() gives the snapshot that was built last, at once.
        assertEquals(1, cache.latest())
        assertEquals(1, builds)
        assertEquals(changedAt, testScheduler.currentTime)
    }

    /**
     * A window that another app opened at the clock's [start] and that slides into place for [slideMillis]. Its
     * position is the distance that it still has to go, 0 once it is in place, so that a snapshot shows where the window
     * was when it was built.
     */
    private class SlidingWindow(private val clock: () -> Long, private val slideMillis: Long) {
        private val start = clock()
        var reads = 0
            private set

        fun position(): Long = maxOf(0L, slideMillis - (clock() - start))

        // The fake read of the bounds, past any cache, that compares the window's position now with a snapshot's.
        val places = object : PlaceCheck<Long> {
            override suspend fun inPlace(value: Long, windows: Set<Int>): Boolean {
                reads++
                assertTrue(windows.isNotEmpty())
                return value == position()
            }

            override fun forget() = Unit
        }
    }

    @Test
    fun aSnapshotOfAWindowThatStillMovesIsBuiltAgainOnceTheWindowHasStopped() = runTest {
        val tracker = ScreenChangeTracker { testScheduler.currentTime }
        val window = SlidingWindow({ testScheduler.currentTime }, slideMillis = 600)
        val built = mutableListOf<Long>()
        val cache = SnapshotCache(tracker, window.places) { window.position().also { built += it } }
        tracker.onScreenChanged(window = 4)

        // The first snapshot, after 150 ms of quiet, shows the window in the middle of its slide; it is not returned.
        assertEquals(0L, cache.current())
        assertTrue(built.first() > 0L)
        assertEquals(0L, built.last())
        // Built again once the window had stopped, and checked by a second read 100 ms later.
        assertTrue(testScheduler.currentTime in 600L..600L + 2 * ScreenChangeTracker.SETTLE_CHECK_MILLIS)

        // The settled snapshot stays until the screen changes again.
        val builds = built.size
        assertEquals(0L, cache.current())
        assertEquals(builds, built.size)
    }

    @Test
    fun aWindowThatNeverStopsCountsAsSettled1500MillisecondsAfterItsEvent() = runTest {
        val tracker = ScreenChangeTracker { testScheduler.currentTime }
        val window = SlidingWindow({ testScheduler.currentTime }, slideMillis = 60_000)
        val builtAt = mutableListOf<Long>()
        val cache = SnapshotCache(tracker, window.places) {
            builtAt += testScheduler.currentTime
            window.position()
        }
        tracker.onScreenChanged(window = 4)

        val value = cache.current()

        // The window counts as settled 1.5 s after its event, and the newest snapshot counts.
        assertEquals(value, 60_000L - builtAt.last())
        assertTrue(testScheduler.currentTime >= ScreenChangeTracker.SETTLE_MAX_MILLIS)
        assertTrue(testScheduler.currentTime <= ScreenChangeTracker.SETTLE_MAX_MILLIS + ScreenChangeTracker.SETTLE_CHECK_MILLIS)
        assertTrue(builtAt.size > 1)
    }

    @Test
    fun noCallWaitsLongerThan1500MillisecondsForTheCheckHoweverManyWindowEventsCome() = runTest {
        val tracker = ScreenChangeTracker { testScheduler.currentTime }
        val window = SlidingWindow({ testScheduler.currentTime }, slideMillis = 600_000)
        val builtAt = mutableListOf<Long>()
        val cache = SnapshotCache(tracker, window.places) {
            builtAt += testScheduler.currentTime
            window.position()
        }
        // A window that moves and fires an event every 100 ms, such as a popup that grows step by step.
        val events = launch {
            while (true) {
                tracker.onScreenChanged(window = 4)
                delay(100)
            }
        }

        val value = withTimeoutOrNull(60_000) { cache.current() }
        events.cancel()

        assertNotNull("The check never ended", value)
        // The screen never goes quiet, so the first build comes after 3 s; the check after it ends 1.5 s later at most.
        assertEquals(QuietBudget.MILLIS, builtAt.first())
        assertTrue(testScheduler.currentTime - builtAt.first() <= ScreenChangeTracker.SETTLE_MAX_MILLIS)
        assertEquals(value, 600_000L - builtAt.last())
    }

    @Test
    fun withoutARecentWindowEventNothingWaitsForTheWindowsOrReadsThemAgain() = runTest {
        val tracker = ScreenChangeTracker { testScheduler.currentTime }
        val window = SlidingWindow({ testScheduler.currentTime }, slideMillis = 0)
        var builds = 0L
        val cache = SnapshotCache(tracker, window.places) { ++builds }

        // Any other change of the screen, such as an event of Kestrel's own window, waits only for the quiet screen.
        tracker.onScreenChanged()
        assertEquals(1L, cache.current())
        assertEquals(ScreenChangeTracker.SNAPSHOT_QUIET_MILLIS, testScheduler.currentTime)

        // A window event that is older than the limit waits for nothing either.
        tracker.onScreenChanged(window = 4)
        delay(ScreenChangeTracker.SETTLE_MAX_MILLIS)
        val before = testScheduler.currentTime
        assertEquals(2L, cache.current())
        assertEquals(before, testScheduler.currentTime)
        assertEquals(0, window.reads)
    }

    private companion object {
        const val BUILD_MILLIS = 100L
    }
}
