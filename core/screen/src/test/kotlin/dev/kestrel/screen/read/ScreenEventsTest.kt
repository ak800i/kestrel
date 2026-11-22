package dev.kestrel.screen.read

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenEventsTest {
    @Test
    fun aNotificationsEventIsKnownByItsTypeAndChangesNoScreen() {
        assertTrue(ScreenEvents.isNotification(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED))
        assertFalse(ScreenEvents.changesScreen(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED))
        assertFalse(ScreenEvents.isNotification(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
    }
}
