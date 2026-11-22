package dev.kestrel.screen.act

import android.view.accessibility.AccessibilityEvent

/**
 * Branch B of design D19 of sub-project 5 (spec 14.1's fallback for a device whose touch watcher misses a real finger):
 * a click or a long click of any element, or a text change of a focused field, in another app's window, while Kestrel
 * neither acts nor settles, is the owner's touch. A scroll never counts, because apps scroll by themselves. [focusedField]
 * asks the app over binder, so it runs last, only for a text change that would otherwise count.
 */
internal object OwnerSignal {
    fun counts(eventType: Int, packageName: String?, ownPackage: String, focusedField: () -> Boolean, busy: Boolean): Boolean {
        if (busy || packageName == null || packageName == ownPackage) return false
        return when (eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> true
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> focusedField()
            else -> false
        }
    }
}
