package dev.kestrel.screen.act

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerSignalTest {
    private val kestrel = "dev.kestrel"

    @Test
    fun aClickOrALongClickInAnotherAppsWindowIsTheOwnersButNotOneInKestrels() {
        assertTrue(OwnerSignal.counts(AccessibilityEvent.TYPE_VIEW_CLICKED, "org.telegram.messenger", kestrel, focusedField = { false }, busy = false))
        assertTrue(OwnerSignal.counts(AccessibilityEvent.TYPE_VIEW_LONG_CLICKED, "com.android.settings", kestrel, focusedField = { false }, busy = false))
        assertFalse(OwnerSignal.counts(AccessibilityEvent.TYPE_VIEW_CLICKED, kestrel, kestrel, focusedField = { false }, busy = false))
        assertFalse(OwnerSignal.counts(AccessibilityEvent.TYPE_VIEW_CLICKED, null, kestrel, focusedField = { false }, busy = false))
    }

    @Test
    fun aTextChangeCountsOnlyInAFocusedFieldAndAScrollNever() {
        assertTrue(OwnerSignal.counts(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, "org.telegram.messenger", kestrel, focusedField = { true }, busy = false))
        assertFalse(OwnerSignal.counts(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, "org.telegram.messenger", kestrel, focusedField = { false }, busy = false))
        // Apps scroll by themselves: a chat that moves to a new message, a carousel.
        assertFalse(OwnerSignal.counts(AccessibilityEvent.TYPE_VIEW_SCROLLED, "org.telegram.messenger", kestrel, focusedField = { true }, busy = false))
        assertFalse(OwnerSignal.counts(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, "org.telegram.messenger", kestrel, focusedField = { true }, busy = false))
    }

    @Test
    fun nothingCountsWhileKestrelActsOrSettles() {
        assertFalse(OwnerSignal.counts(AccessibilityEvent.TYPE_VIEW_CLICKED, "org.telegram.messenger", kestrel, focusedField = { false }, busy = true))
        assertFalse(OwnerSignal.counts(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, "org.telegram.messenger", kestrel, focusedField = { true }, busy = true))
    }

    @Test
    fun theFieldsFocusIsReadOnlyForATextChangeThatCouldCount() {
        // Reading the focus asks the app over binder on the main thread (task 5B-6's review), so the cheap checks go first.
        val never: () -> Boolean = { throw AssertionError("The focus was read") }
        assertFalse(OwnerSignal.counts(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, "org.telegram.messenger", kestrel, focusedField = never, busy = true))
        assertFalse(OwnerSignal.counts(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, kestrel, kestrel, focusedField = never, busy = false))
        assertFalse(OwnerSignal.counts(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, null, kestrel, focusedField = never, busy = false))
        assertTrue(OwnerSignal.counts(AccessibilityEvent.TYPE_VIEW_CLICKED, "org.telegram.messenger", kestrel, focusedField = never, busy = false))
        assertFalse(OwnerSignal.counts(AccessibilityEvent.TYPE_VIEW_SCROLLED, "org.telegram.messenger", kestrel, focusedField = never, busy = false))
    }
}