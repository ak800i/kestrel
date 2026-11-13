package dev.kestrel.screen.read

import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
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
        assertEquals(ScreenChangeTracker.MAX_WAIT_MILLIS, testScheduler.currentTime)
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
    fun onlyEventsThatCanChangeTheScreenCount() {
        assertTrue(ScreenEvents.changesScreen(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
        assertTrue(ScreenEvents.changesScreen(AccessibilityEvent.TYPE_WINDOWS_CHANGED))
        assertTrue(ScreenEvents.changesScreen(AccessibilityEvent.TYPE_VIEW_SCROLLED))
        assertFalse(ScreenEvents.changesScreen(AccessibilityEvent.TYPE_VIEW_HOVER_ENTER))
        assertFalse(ScreenEvents.changesScreen(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED))
    }
}
