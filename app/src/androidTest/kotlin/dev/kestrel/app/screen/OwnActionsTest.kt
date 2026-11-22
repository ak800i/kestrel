package dev.kestrel.app.screen

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.screen.act.ActionMethod
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.act.OwnActions
import dev.kestrel.screen.act.Touch
import dev.kestrel.screen.model.ScreenElement
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Kestrel's own actions and the owner's finger (design D19 of sub-project 5): the probe of what Kestrel's actions look
 * like to the touch watcher on this device, and the rule that keeps a click's echo from cancelling a task while the
 * finger still does.
 */
@RunWith(AndroidJUnit4::class)
class OwnActionsTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val session get() = screen.session

    /** What reached the watcher within [PROBE_MILLIS] after one action of the probe returned, each with its delay. */
    private class Probe(val case: String, val touches: List<Pair<Touch, Long>>) {
        /** One line of numbers only, for logcat's tag KestrelProbe. */
        fun line(sdk: Int): String {
            val first = touches.firstOrNull()
            val touch = first?.first
            return "case=$case sdk=$sdk touches=${touches.size} device=${touch?.deviceId ?: "none"} source=${touch?.source ?: "none"} " +
                "tool=${touch?.toolType ?: "none"} flags=${touch?.flags?.let { "0x" + Integer.toHexString(it) } ?: "none"} delayMs=${first?.second ?: "none"}"
        }
    }

    private suspend fun probe(case: String, act: suspend () -> Unit): Probe = coroutineScope {
        val seen = Channel<Pair<Touch, Long>>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { session.touches.collect { seen.send(it to SystemClock.uptimeMillis()) } }
        act()
        val returned = SystemClock.uptimeMillis()
        delay(PROBE_MILLIS)
        collector.cancel()
        seen.close()
        val touches = mutableListOf<Pair<Touch, Long>>()
        for ((touch, at) in seen) touches += touch to (at - returned)
        Probe(case, touches)
    }

    /** Performs [action] on the element's node through Kestrel's accessibility service, without OwnActions, and gives Android's answer. */
    private suspend fun perform(predicate: (ScreenElement) -> Boolean, action: Int, arguments: Bundle? = null): Boolean {
        val element = screen.awaitElement(predicate = predicate)
        val node = checkNotNull(session.snapshots.current().node(element.key))
        return withContext(Dispatchers.IO) { node.performAction(action, arguments) }
    }

    private fun id(name: String): (ScreenElement) -> Boolean = { it.viewId == "$FIXTURES:id/$name" }

    @Test
    fun probeRecordsWhatKestrelsActionsLookLikeToTheWatcher() = runBlocking {
        val probes = mutableListOf<Probe>()
        TestFinger(screen.automation).use { finger ->
            screen.open("ViewsActivity", "Views fixture")
            assertTrue(session.startTouchWatcher())
            screen.awaitInputWindow(WATCHER)
            probes += probe("click") { assertTrue(perform(id("send_button"), AccessibilityNodeInfo.ACTION_CLICK)) }
            probes += probe("long-click") { assertTrue(perform(id("send_button"), AccessibilityNodeInfo.ACTION_LONG_CLICK)) }
            val text = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "probe") }
            probes += probe("set-text") { perform(id("message_field"), AccessibilityNodeInfo.ACTION_SET_TEXT, text) }
            probes += probe("ime-focus") { perform(id("strict_field"), AccessibilityNodeInfo.ACTION_FOCUS) }
            val target = screen.awaitElement { it.label == "Touch target" }.bounds
            probes += probe("gesture") { session.actions.tapAt(target.centerX, target.centerY) }
            probes += probe("finger") { finger.tap(target.centerX, target.centerY) }
            probes += probe("input-tap") { screen.shell("input tap ${target.centerX} ${target.centerY}") }
            screen.open("ScrollActivity", "Scroll fixture")
            probes += probe("scroll") { perform({ it.scrollable }, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
            screen.open("PressActivity", "Press fixture")
            probes += probe("refused-click") { assertFalse(perform({ it.label == "DEAF" }, AccessibilityNodeInfo.ACTION_CLICK)) }
        }
        val sdk = Build.VERSION.SDK_INT
        for (probe in probes) Log.i(TAG, probe.line(sdk))
        if (sdk >= 34) {
            // The credits are made for clicks and long clicks only, and live twice the longest echo's delay (D19).
            for (case in listOf("set-text", "ime-focus", "scroll")) {
                assertEquals("$case reached the watcher: ${probes.single { it.case == case }.line(sdk)}", 0, probes.single { it.case == case }.touches.size)
            }
            // Android 17 sends a click's echo before the app answers, so a refused click may echo once, inside the call,
            // where the call's credit takes it; a later echo would count as the owner's touch.
            val refused = probes.single { it.case == "refused-click" }
            assertTrue("refused-click reached the watcher more than once: ${refused.line(sdk)}", refused.touches.size <= 1)
            refused.touches.firstOrNull()?.let { (touch, delayMillis) ->
                assertTrue("refused-click's touch is not a click's echo inside the call: ${refused.line(sdk)}", touch.deviceId == 0 && delayMillis <= 0)
            }
            for (case in listOf("click", "long-click")) {
                for ((_, delayMillis) in probes.single { it.case == case }.touches) {
                    assertTrue(String.format(Locale.ROOT, "%s echoed after %d ms", case, delayMillis), delayMillis <= OwnActions.CREDIT_MILLIS / 2)
                }
            }
        }
    }

    @Test
    fun aFingerOneSecondAfterAnEchoedClickIsTheOwners() = runBlocking {
        TestFinger(screen.automation).use { finger ->
            screen.open("ViewsActivity", "Views fixture")
            val send = screen.awaitElement(predicate = id("send_button"))
            val target = screen.awaitElement { it.label == "Touch target" }.bounds
            assertTrue(session.startTouchWatcher())
            screen.awaitInputWindow(WATCHER)
            val owners = Channel<Unit>(Channel.UNLIMITED)
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { session.ownerTouches.collect { owners.send(it) } }

            assertEquals(ActionResult.Done(ActionMethod.ACTION), session.actions.tap(send.key))
            screen.awaitElement { it.label == "Clicked Send" }
            delay(1_000)
            assertTrue("Kestrel's own click was reported as the owner's touch", owners.tryReceive().isFailure)

            finger.tap(target.centerX, target.centerY)
            withTimeout(3_000) { owners.receive() }
            collector.cancel()
        }
        Unit
    }

    @Test
    fun aFingerRightAfterTheWatcherStartsIsTheOwnersAfterAnEarlierClick() = runBlocking {
        TestFinger(screen.automation).use { finger ->
            screen.open("ViewsActivity", "Views fixture")
            val send = screen.awaitElement(predicate = id("send_button"))
            val target = screen.awaitElement { it.label == "Touch target" }.bounds
            assertTrue(session.startTouchWatcher())
            screen.awaitInputWindow(WATCHER)
            val owners = Channel<Unit>(Channel.UNLIMITED)
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { session.ownerTouches.collect { owners.send(it) } }
            session.actions.tap(send.key)
            screen.awaitElement { it.label == "Clicked Send" }

            // As when a task starts the watcher again: what Kestrel did before can hide no touch of the owner's.
            session.stopTouchWatcher()
            assertTrue(session.startTouchWatcher())
            screen.awaitInputWindow(WATCHER)
            finger.tap(target.centerX, target.centerY)

            withTimeout(3_000) { owners.receive() }
            delay(500)
            assertTrue("More than the finger's touch was reported", owners.tryReceive().isFailure)
            collector.cancel()
        }
        Unit
    }

    private companion object {
        const val WATCHER = "Kestrel touch watcher"
        const val TAG = "KestrelProbe"
        const val PROBE_MILLIS = 1_000L
    }
}
