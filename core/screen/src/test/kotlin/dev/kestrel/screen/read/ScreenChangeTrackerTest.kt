package dev.kestrel.screen.read

import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenChangeTrackerTest {
    @Test
    fun awaitQuietReturnsOnceTheScreenHasBeenQuietForTheQuietTime() = runTest {
        val tracker = ScreenChangeTracker { testScheduler.currentTime }
        launch {
            delay(100)
            tracker.onScreenChanged()
        }

        assertTrue(tracker.awaitQuiet(quietMillis = 200))
        assertEquals(300, testScheduler.currentTime)
    }

    @Test
    fun awaitQuietGivesUpAfterTheLongestWait() = runTest {
        val tracker = ScreenChangeTracker { testScheduler.currentTime }
        val changes = launch {
            while (true) {
                tracker.onScreenChanged()
                delay(50)
            }
        }

        assertFalse(tracker.awaitQuiet(quietMillis = 200))
        assertEquals(QuietBudget.MILLIS, testScheduler.currentTime)
        changes.cancel()
    }

    @Test
    fun quietTimeCountsFromAnActionEvenWhenTheScreenWasAlreadyQuiet() = runTest {
        val tracker = ScreenChangeTracker { testScheduler.currentTime }
        delay(1_000)

        assertTrue(tracker.awaitQuiet(quietMillis = 200, since = testScheduler.currentTime))
        assertEquals(1_200, testScheduler.currentTime)
    }

    @Test
    fun theWaitsOfOneCommandOnAScreenThatNeverGoesQuietTakeItsBudgetInAll() = runTest {
        val tracker = ScreenChangeTracker { testScheduler.currentTime }
        // A change every 110 ms, as SystemUI's shade sends while it sets its date again and again.
        val changes = launch {
            while (true) {
                tracker.onScreenChanged()
                delay(110)
            }
        }

        val quiet = withQuietBudget {
            listOf(
                tracker.awaitQuiet(ScreenChangeTracker.SNAPSHOT_QUIET_MILLIS),
                tracker.awaitQuiet(ScreenChangeTracker.ACTION_QUIET_MILLIS, since = tracker.now()),
                tracker.awaitQuiet(ScreenChangeTracker.SNAPSHOT_QUIET_MILLIS),
                // A run inside the command keeps the command's budget.
                withQuietBudget { tracker.awaitQuiet(ScreenChangeTracker.ACTION_QUIET_MILLIS, since = tracker.now()) },
            )
        }
        changes.cancel()

        // The first wait spends the budget; each wait after an action then still lasts its own quiet time.
        assertEquals(listOf(false, false, false, false), quiet)
        assertEquals(QuietBudget.MILLIS + 2 * ScreenChangeTracker.ACTION_QUIET_MILLIS, testScheduler.currentTime)
    }

    @Test
    fun aScreenThatGoesQuietSettlesWithinACommandAsItDoesOutsideOne() = runTest {
        val tracker = ScreenChangeTracker { testScheduler.currentTime }
        val changes = launch {
            repeat(6) {
                tracker.onScreenChanged()
                delay(50)
            }
        }
        val budget = QuietBudget()

        withContext(budget) {
            // The last change comes at 250 ms, so the snapshot's wait ends 150 ms later.
            assertTrue(tracker.awaitQuiet(ScreenChangeTracker.SNAPSHOT_QUIET_MILLIS))
            assertEquals(400, testScheduler.currentTime)
            // The settle wait after an action counts from the action.
            assertTrue(tracker.awaitQuiet(ScreenChangeTracker.ACTION_QUIET_MILLIS, since = tracker.now()))
            assertEquals(600, testScheduler.currentTime)
        }
        changes.join()

        // Waits that end because the screen went quiet cost nothing.
        assertEquals(QuietBudget.MILLIS, budget.remainingMillis)
    }

    @Test
    fun aCommandWhoseScreenSettlesAfterEachActionNeverSpendsItsBudget() = runTest {
        val tracker = ScreenChangeTracker { testScheduler.currentTime }
        val budget = QuietBudget()

        withContext(budget) {
            repeat(10) {
                // Each action makes the app change its screen every 50 ms for 300 ms, as an animation does.
                val animation = launch {
                    repeat(7) {
                        tracker.onScreenChanged()
                        delay(50)
                    }
                }
                val acted = tracker.now()
                assertTrue(tracker.awaitQuiet(ScreenChangeTracker.ACTION_QUIET_MILLIS, since = acted))
                assertEquals(acted + 300 + ScreenChangeTracker.ACTION_QUIET_MILLIS, testScheduler.currentTime)
                animation.join()
            }
        }

        assertEquals(QuietBudget.MILLIS, budget.remainingMillis)
    }

    @Test
    fun aWaitAfterAnActionLastsItsQuietTimeAlsoWhenTheBudgetIsSpent() = runTest {
        val tracker = ScreenChangeTracker { testScheduler.currentTime }
        val changes = launch {
            while (true) {
                tracker.onScreenChanged()
                delay(110)
            }
        }

        withContext(QuietBudget(0)) {
            assertFalse(tracker.awaitQuiet(ScreenChangeTracker.ACTION_QUIET_MILLIS, since = tracker.now()))
            assertEquals(ScreenChangeTracker.ACTION_QUIET_MILLIS, testScheduler.currentTime)
            // A wait for a snapshot has no floor, so it ends at once.
            assertFalse(tracker.awaitQuiet(ScreenChangeTracker.SNAPSHOT_QUIET_MILLIS))
            assertEquals(ScreenChangeTracker.ACTION_QUIET_MILLIS, testScheduler.currentTime)
        }
        changes.cancel()
    }

    @Test
    fun outsideACommandEachWaitHasABudgetOfItsOwn() = runTest {
        val tracker = ScreenChangeTracker { testScheduler.currentTime }
        val changes = launch {
            while (true) {
                tracker.onScreenChanged()
                delay(50)
            }
        }

        assertFalse(tracker.awaitQuiet(ScreenChangeTracker.SNAPSHOT_QUIET_MILLIS))
        assertFalse(tracker.awaitQuiet(ScreenChangeTracker.SNAPSHOT_QUIET_MILLIS))
        changes.cancel()

        assertEquals(2 * QuietBudget.MILLIS, testScheduler.currentTime)
    }

    @Test
    fun onlyEventsThatCanChangeTheScreenCount() {
        assertTrue(ScreenEvents.changesScreen(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
        assertTrue(ScreenEvents.changesScreen(AccessibilityEvent.TYPE_WINDOWS_CHANGED))
        assertTrue(ScreenEvents.changesScreen(AccessibilityEvent.TYPE_VIEW_SCROLLED))
        assertFalse(ScreenEvents.changesScreen(AccessibilityEvent.TYPE_VIEW_HOVER_ENTER))
        assertFalse(ScreenEvents.changesScreen(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED))
    }

    @Test
    fun eventsOfAnotherAppsWindowMayMoveItAndKestrelsOverlaysNever() {
        val events = WindowEvents(ownPackage = "dev.kestrel", overlayClass = OVERLAY)
        val added = AccessibilityEvent.WINDOWS_CHANGE_ADDED
        val removed = AccessibilityEvent.WINDOWS_CHANGE_REMOVED

        // An app that opens, a window that moves or goes, and the shade's window.
        assertTrue(events.movesWindow(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.example.mail", "com.example.mail.Inbox", 7, 0))
        assertTrue(events.movesWindow(AccessibilityEvent.TYPE_WINDOWS_CHANGED, null, null, 7, added))
        assertTrue(events.movesWindow(AccessibilityEvent.TYPE_WINDOWS_CHANGED, null, null, 7, AccessibilityEvent.WINDOWS_CHANGE_BOUNDS))
        assertTrue(events.movesWindow(AccessibilityEvent.TYPE_WINDOWS_CHANGED, null, null, 3, removed))
        // A window that only became active, and a change of content, move nothing.
        assertFalse(events.movesWindow(AccessibilityEvent.TYPE_WINDOWS_CHANGED, null, null, 7, AccessibilityEvent.WINDOWS_CHANGE_ACTIVE))
        assertFalse(events.movesWindow(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, "com.example.mail", null, 7, 0))

        // Kestrel's touch watcher: its state event names Kestrel and the overlays' root class, and the change of the
        // window list that follows names only the window.
        assertFalse(events.movesWindow(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "dev.kestrel", OVERLAY, 9, 0))
        assertFalse(events.movesWindow(AccessibilityEvent.TYPE_WINDOWS_CHANGED, null, null, 9, added))
        assertFalse(events.movesWindow(AccessibilityEvent.TYPE_WINDOWS_CHANGED, null, null, 9, removed))
        // Once removed, the ID is no longer Kestrel's.
        assertTrue(events.movesWindow(AccessibilityEvent.TYPE_WINDOWS_CHANGED, null, null, 9, added))

        // Kestrel's settings app is an app like another: its window slides into place too.
        assertTrue(events.movesWindow(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "dev.kestrel", "dev.kestrel.app.MainActivity", 11, 0))
        assertTrue(events.movesWindow(AccessibilityEvent.TYPE_WINDOWS_CHANGED, null, null, 11, added))
    }

    @Test
    fun kestrelsOverlaysAreRememberedOnlyByTheirNewestSixteenIds() {
        val events = WindowEvents(ownPackage = "dev.kestrel", overlayClass = OVERLAY)
        val added = AccessibilityEvent.WINDOWS_CHANGE_ADDED
        // Each overlay that is shown is a new window; those that take no touches never fire a removal.
        for (id in 100..116) assertFalse(events.movesWindow(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "dev.kestrel", OVERLAY, id, 0))

        assertTrue(events.movesWindow(AccessibilityEvent.TYPE_WINDOWS_CHANGED, null, null, 100, added))
        assertFalse(events.movesWindow(AccessibilityEvent.TYPE_WINDOWS_CHANGED, null, null, 101, added))
        assertFalse(events.movesWindow(AccessibilityEvent.TYPE_WINDOWS_CHANGED, null, null, 116, added))
    }

    @Test
    fun aPassedCheckSettlesOnlyTheWindowsThatEventsNamedBeforeItsSnapshotWasBuilt() {
        var now = 0L
        val tracker = ScreenChangeTracker { now }
        tracker.onScreenChanged(window = 1)
        val beforeTheBuild = tracker.windowEventCount()
        // An event during the build names another window, which the passed check never saw in place.
        tracker.onScreenChanged(window = 2)

        tracker.settled(beforeTheBuild)

        assertEquals(setOf(2), tracker.movingWindows())
        now += ScreenChangeTracker.SETTLE_MAX_MILLIS
        assertEquals(emptySet<Int>(), tracker.movingWindows())
    }

    private companion object {
        const val OVERLAY = "dev.kestrel.screen.overlay.Overlay"
    }
}
