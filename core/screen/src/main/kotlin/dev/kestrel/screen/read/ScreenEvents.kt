package dev.kestrel.screen.read

import android.view.accessibility.AccessibilityEvent

/** Which accessibility events mean that the screen may have changed. */
object ScreenEvents {
    private const val NOT_SCREEN_CHANGES =
        AccessibilityEvent.TYPE_VIEW_HOVER_ENTER or
            AccessibilityEvent.TYPE_VIEW_HOVER_EXIT or
            AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START or
            AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END or
            AccessibilityEvent.TYPE_GESTURE_DETECTION_START or
            AccessibilityEvent.TYPE_GESTURE_DETECTION_END or
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START or
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END or
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED or
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED or
            AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY or
            AccessibilityEvent.TYPE_ASSIST_READING_CONTEXT or
            AccessibilityEvent.TYPE_SPEECH_STATE_CHANGE or
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED

    fun changesScreen(eventType: Int): Boolean = eventType and NOT_SCREEN_CHANGES == 0

    /**
     * A notification's event, which carries the whole notification unredacted on every Android version (r54): Kestrel
     * reads nothing of it but its type (design D9 of sub-project 5; Appendix B61).
     */
    fun isNotification(eventType: Int): Boolean = eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
}
