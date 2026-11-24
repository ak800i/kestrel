package dev.kestrel.screen.act

import android.view.accessibility.AccessibilityEvent
import dev.kestrel.screen.act.OwnerSignal.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerSignalTest {
    private val kestrel = "dev.kestrel"
    private val firefox = "org.mozilla.firefox"

    private fun judge(eventType: Int, packageName: String?, busy: Boolean = false, keyboard: () -> Boolean = { true }, focusedField: () -> Boolean = { false }) =
        OwnerSignal.judge(eventType, packageName, kestrel, busy, keyboard, focusedField)

    private fun counts(eventType: Int, packageName: String?, busy: Boolean = false, keyboard: () -> Boolean = { true }, focusedField: () -> Boolean = { false }) =
        judge(eventType, packageName, busy, keyboard, focusedField) == Verdict.OWNER

    @Test
    fun aClickOrALongClickInAnotherAppsWindowIsTheOwnersButNotOneInKestrels() {
        assertTrue(counts(AccessibilityEvent.TYPE_VIEW_CLICKED, "org.telegram.messenger"))
        assertTrue(counts(AccessibilityEvent.TYPE_VIEW_LONG_CLICKED, "com.android.settings"))
        assertFalse(counts(AccessibilityEvent.TYPE_VIEW_CLICKED, kestrel))
        assertFalse(counts(AccessibilityEvent.TYPE_VIEW_CLICKED, null))
    }

    @Test
    fun aTextChangeCountsOnlyInAFocusedFieldAndAScrollNever() {
        assertTrue(counts(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, "org.telegram.messenger", focusedField = { true }))
        assertFalse(counts(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, "org.telegram.messenger", focusedField = { false }))
        // Apps scroll by themselves: a chat that moves to a new message, a carousel.
        assertFalse(counts(AccessibilityEvent.TYPE_VIEW_SCROLLED, "org.telegram.messenger", focusedField = { true }))
        assertFalse(counts(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, "org.telegram.messenger", focusedField = { true }))
    }

    @Test
    fun nothingCountsWhileKestrelActsOrSettles() {
        assertFalse(counts(AccessibilityEvent.TYPE_VIEW_CLICKED, "org.telegram.messenger", busy = true))
        assertFalse(counts(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, "org.telegram.messenger", busy = true, focusedField = { true }))
    }

    @Test
    fun theFieldsFocusAndTheKeyboardAreReadOnlyForATextChangeThatCouldCount() {
        // Reading the focus asks the app over binder on the main thread (task 5B-6's review), so the cheap checks go first.
        val never: () -> Boolean = { throw AssertionError("The focus or the keyboard was read") }
        assertFalse(counts(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, "org.telegram.messenger", busy = true, keyboard = never, focusedField = never))
        assertFalse(counts(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, kestrel, keyboard = never, focusedField = never))
        assertFalse(counts(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, null, keyboard = never, focusedField = never))
        assertTrue(counts(AccessibilityEvent.TYPE_VIEW_CLICKED, "org.telegram.messenger", keyboard = never, focusedField = never))
        assertFalse(counts(AccessibilityEvent.TYPE_VIEW_SCROLLED, "org.telegram.messenger", keyboard = never, focusedField = never))
        // Without a keyboard the field's focus no longer matters.
        assertFalse(counts(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, "org.telegram.messenger", keyboard = { false }, focusedField = never))
    }

    // The A71's evidence (fix-touch-b): when Firefox opens a page by intent, its web view, which holds the focus, reports a
    // text change by itself, with no keyboard on the screen and no finger on the touchscreen. Branch B took it for the
    // owner's touch and cancelled the task while the model was thinking.
    @Test
    fun anAppsOwnTextChangeAfterKestrelOpenedItByIntentIsNotTheOwnersWhileNoKeyboardShows() {
        var now = 1_000L
        val own = branchB { now }
        // open_url as ScreenActions.openUri runs it: the intent and its settle wait, and then the model thinks.
        own.begin()
        now += 200
        own.end()
        now += OwnActions.OWN_WINDOW_TAIL_MILLIS + 2_000

        assertFalse(own.busy())
        assertEquals(
            Verdict.NO_KEYBOARD,
            judge(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, firefox, own.busy(), keyboard = { false }, focusedField = { true }),
        )
    }

    @Test
    fun theOwnersTouchDuringOrRightAfterKestrelsIntentStillCounts() {
        var now = 1_000L
        val own = branchB { now }
        // During the intent's settle wait, the watcher's touch is the owner's: an intent creates no credit.
        own.begin()
        assertTrue(own.classify(deviceId = 3, inputSource = 4098, toolType = 1, flags = 0, gestureRunning = false).owner)
        now += 200
        own.end()

        // Right after the settle wait, his tap on the app, and his typing with the keyboard, are his.
        now += OwnActions.OWN_WINDOW_TAIL_MILLIS
        assertFalse(own.busy())
        assertEquals(Verdict.OWNER, judge(AccessibilityEvent.TYPE_VIEW_CLICKED, firefox, own.busy(), keyboard = { false }))
        assertEquals(Verdict.OWNER, judge(AccessibilityEvent.TYPE_VIEW_LONG_CLICKED, firefox, own.busy(), keyboard = { false }))
        assertEquals(Verdict.OWNER, judge(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, firefox, own.busy(), keyboard = { true }, focusedField = { true }))
    }

    private fun branchB(clock: () -> Long) = OwnActions(clock, sdk = 33).also {
        it.source = TouchSource(TouchRule.CREDITS, TouchBranch.B)
        it.watching(true)
    }
}
