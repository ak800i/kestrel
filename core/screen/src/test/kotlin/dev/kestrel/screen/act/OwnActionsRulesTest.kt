package dev.kestrel.screen.act

import android.view.KeyCharacterMap
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnActionsRulesTest {
    private var now = 1_000L

    private fun own(sdk: Int = 37, rule: TouchRule? = null) = OwnActions({ now }, sdk).also { own ->
        rule?.let { own.source = TouchSource(it) }
        own.watching(true)
    }

    // A touch of the owner's finger on the touchscreen, or an echo of Kestrel's click, which looks the same on Android 17.
    private fun touch(own: OwnActions, flags: Int = 0) = own.classify(deviceId = 0, inputSource = 4098, toolType = 1, flags = flags, gestureRunning = false)

    @Test
    fun theDefaultRuleIsNoneOnAndroid13AndCreditsAboveIt() {
        assertEquals(TouchSource(TouchRule.NONE, TouchBranch.A), TouchSource.defaultFor(33))
        assertEquals(TouchSource(TouchRule.CREDITS, TouchBranch.A), TouchSource.defaultFor(34))
        assertEquals(TouchSource(TouchRule.CREDITS, TouchBranch.A), TouchSource.defaultFor(37))
    }

    @Test
    fun theVirtualDevicesTouchDuringKestrelsGestureIsKestrelsByEveryRule() {
        for (rule in TouchRule.entries) {
            val own = own(rule = rule)
            assertFalse(rule.name, own.classify(KeyCharacterMap.VIRTUAL_KEYBOARD, 4098, 1, 0, gestureRunning = true).owner)
            assertTrue(rule.name, own.classify(KeyCharacterMap.VIRTUAL_KEYBOARD, 4098, 1, 0, gestureRunning = false).owner)
            assertTrue(rule.name, own.classify(0, 4098, 1, 0, gestureRunning = true).owner)
        }
    }

    @Test
    fun anAcceptedClickGivesOneCreditThatItsEchoUses() {
        val own = own()
        assertTrue(own.node(OwnActions.Kind.CLICK) { true })
        now += 50

        val echo = touch(own)
        assertFalse(echo.owner)
        assertTrue(echo.credited)
        assertEquals(50L, echo.sinceNodeActionMillis)
        // One credit per click: the next touch is the owner's.
        assertTrue(touch(own).owner)
    }

    @Test
    fun anEchoThatComesWhileTheClickStillRunsUsesItsCredit() {
        val own = own()
        var echo: Touch? = null

        own.node(OwnActions.Kind.LONG_CLICK) {
            echo = touch(own)
            true
        }

        assertFalse(checkNotNull(echo).owner)
        assertTrue(touch(own).owner)
    }

    @Test
    fun aCreditEndsAfterItsTimeAndALateEchoIsTheOwnersWithItsDelay() {
        val own = own()
        own.node(OwnActions.Kind.CLICK) { true }
        now += OwnActions.CREDIT_MILLIS + 1

        val late = touch(own)
        assertTrue(late.owner)
        assertEquals(OwnActions.CREDIT_MILLIS + 1, late.sinceNodeActionMillis)
    }

    @Test
    fun aRefusedClickAndTheKindsThatDoNotEchoGiveNoCredit() {
        val own = own()

        own.node(OwnActions.Kind.CLICK) { false }
        own.node(OwnActions.Kind.SCROLL) { true }
        own.node(OwnActions.Kind.SET_TEXT) { true }
        own.node(OwnActions.Kind.FOCUS) { true }

        assertTrue(touch(own).owner)
        assertEquals(OwnActions.Kind.CLICK, OwnActions.kindOf(AccessibilityNodeInfo.ACTION_CLICK))
        assertEquals(OwnActions.Kind.FOCUS, OwnActions.kindOf(AccessibilityNodeInfo.ACTION_FOCUS))
        assertEquals(setOf(OwnActions.Kind.CLICK, OwnActions.Kind.LONG_CLICK), OwnActions.ECHOING)
    }

    @Test
    fun startingTheWatcherOrClearingForgetsTheCreditsAndNoneComeWhileItIsOff() {
        val own = own()
        own.node(OwnActions.Kind.CLICK) { true }
        own.watching(true)
        assertTrue("a start forgets the credits", touch(own).owner)

        own.node(OwnActions.Kind.CLICK) { true }
        own.clear()
        assertTrue("a card or a question forgets the credits", touch(own).owner)

        own.watching(false)
        own.node(OwnActions.Kind.CLICK) { true }
        own.watching(true)
        assertTrue("an action made before the watcher ran hides no touch", touch(own).owner)
    }

    @Test
    fun theFieldRuleUsesTheAccessibilityFlagFromAndroid17AndCreditsBelowIt() {
        val field = own(sdk = 37, rule = TouchRule.FIELD)
        assertFalse(touch(field, flags = 0x800).owner)
        assertTrue(touch(field).owner)

        val older = own(sdk = 35, rule = TouchRule.FIELD)
        assertTrue("a real touch during a gesture carries the flag on Android 13 to 16", touch(older, flags = 0x800).owner)
        older.node(OwnActions.Kind.CLICK) { true }
        assertFalse(touch(older).owner)

        val none = own(sdk = 37, rule = TouchRule.NONE)
        none.node(OwnActions.Kind.CLICK) { true }
        assertTrue(touch(none).owner)
    }

    @Test
    fun kestrelIsBusyDuringItsActionsAndShortlyAfterThem() {
        val own = own()
        assertFalse(own.busy())

        own.begin()
        assertTrue(own.busy())
        own.end()
        assertTrue(own.busy())
        now += OwnActions.OWN_WINDOW_TAIL_MILLIS
        assertFalse(own.busy())

        own.node(OwnActions.Kind.SCROLL) { true }
        assertTrue(own.busy())
    }

    @Test
    fun aCancelledGestureIsTheOwnersTouchUpToAndroid15WhileTheWatcherRuns() {
        val reported = mutableListOf<Touch>()

        own(sdk = 35).apply { report = { reported += it } }.gestureCancelled()
        assertEquals(listOf(OwnActions.signal()), reported)
        assertTrue(reported.single().owner)

        own(sdk = 36).apply { report = { reported += it } }.gestureCancelled()
        own(sdk = 33).apply { report = { reported += it }; watching(false) }.gestureCancelled()
        assertEquals(1, reported.size)
    }
}
