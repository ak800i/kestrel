package dev.kestrel.screen.act

import android.view.accessibility.AccessibilityEvent

/**
 * Branch B of design D19 of sub-project 5 (spec 14.1's fallback for a device whose touch watcher misses a real finger):
 * a click or a long click of any element in another app's window, or a text change of a focused field there while a
 * keyboard shows, while Kestrel neither acts nor settles, is the owner's touch. A scroll never counts, because apps
 * scroll by themselves. A text change without a keyboard never counts either, because apps fill their own fields: on the
 * A71, Firefox's web view reports one each time it opens a page, with no finger on the screen; the owner types only
 * with a keyboard, and his tap on a field is a click. [keyboardShown] reads Android's windows and [focusedField] asks
 * the app over binder, so they run last, only for a text change that would otherwise count.
 */
internal object OwnerSignal {
    /** Why an event is, or is not, the owner's touch. */
    enum class Verdict { OWNER, NOT_A_SIGNAL, KESTRELS, NO_KEYBOARD, NOT_FOCUSED }

    fun judge(eventType: Int, packageName: String?, ownPackage: String, busy: Boolean, keyboardShown: () -> Boolean, focusedField: () -> Boolean): Verdict {
        if (packageName == null || packageName == ownPackage) return Verdict.NOT_A_SIGNAL
        return when (eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> if (busy) Verdict.KESTRELS else Verdict.OWNER
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> when {
                busy -> Verdict.KESTRELS
                !keyboardShown() -> Verdict.NO_KEYBOARD
                !focusedField() -> Verdict.NOT_FOCUSED
                else -> Verdict.OWNER
            }
            else -> Verdict.NOT_A_SIGNAL
        }
    }

    /**
     * The log line of [verdict] for an event of [eventType] in [packageName]'s view [className], or null for an event
     * that is no signal or is Kestrel's own. Each other verdict is logged: a signal, so that a false cancel shows its
     * cause, and a text change that did not count, without a keyboard or without the focus, so that a missed one does.
     * It names the event's type, app and class, never its text.
     */
    fun logLine(verdict: Verdict, eventType: String, packageName: String?, className: String?): String? =
        if (verdict == Verdict.NOT_A_SIGNAL || verdict == Verdict.KESTRELS) null else "branch B: $eventType in $packageName ($className): $verdict"
}
