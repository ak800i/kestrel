package dev.kestrel.app.conversation

import android.content.Intent
import android.graphics.Point
import android.os.Build
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import dev.kestrel.agent.testing.ScriptedModel
import dev.kestrel.agent.testing.ScriptedModel.Companion.call
import dev.kestrel.app.R
import dev.kestrel.app.conversation.screen.ConversationActivity
import dev.kestrel.app.conversation.screen.FIELD_TAG
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.app.screen.ScreenServiceRule.Companion.KESTREL
import dev.kestrel.app.voice.VoiceTests
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/**
 * The typed-input boundary on kestrel-api37 (design 12.2 of sub-project 7): a tap that passes through another app's
 * window over the conversation screen sends nothing, an intent that names the screen with words and data puts nothing in
 * its field and does not become the screen's intent, and only accessibility tools may act on the screen's views.
 */
@RunWith(AndroidJUnit4::class)
class TypedBoundaryTest {
    @get:Rule(order = 0)
    val screen = ScreenServiceRule()

    @get:Rule(order = 1)
    val keyboard = ScreenKeyboard.rule

    @get:Rule(order = 2)
    val compose = createAndroidComposeRule<ConversationActivity>()

    private val conversations get() = TypedTests.graph.conversations
    private var overlayMode = DEFAULT_MODE

    @Before
    fun allowOverlays() {
        overlayMode = OVERLAY_MODE.find(screen.shell("appops get $FIXTURES SYSTEM_ALERT_WINDOW"))?.groupValues?.get(1) ?: DEFAULT_MODE
        screen.shell("appops set $FIXTURES SYSTEM_ALERT_WINDOW allow")
        TypedTests.prepare(ScriptedModel(listOf(call("fail", null, null, null, "reason" to "I stop here."))))
    }

    @After
    fun putBack() {
        try {
            TypedTests.finish()
        } finally {
            screen.shell("am force-stop $FIXTURES")
            screen.shell("appops set $FIXTURES SYSTEM_ALERT_WINDOW $overlayMode")
        }
    }

    @Test
    fun aTapThatPassesThroughAnotherAppsWindowSendsNothing() {
        screen.open("ObscuringActivity", "Obscuring fixture")
        TypedTests.bringScreen(screen, compose)
        check(VoiceTests.waitUntil(10_000) { screen.showsWindow(OVERLAY) }) { "The fixture's window is not over the screen" }
        runBlocking { screen.awaitInputWindow(OVERLAY) }
        val before = conversations.view.value.command
        val words = TypedTests.marked(WORDS)
        compose.onNodeWithTag(FIELD_TAG).performTextInput(words)

        val send = sendCentre()
        tap(send)
        Thread.sleep(SETTLE_MILLIS)

        assertEquals(before, conversations.view.value.command)
        compose.onNodeWithTag(FIELD_TAG).assertTextContains(words)

        // Without the window, a tap on the same point sends: the tap above was on Send.
        screen.shell("am force-stop $FIXTURES")
        check(VoiceTests.waitUntil(10_000) { !screen.showsWindow(OVERLAY) }) { "The fixture's window stayed" }
        assertEquals("Send moved between the two taps", send, sendCentre())
        tap(send)
        check(VoiceTests.waitUntil(10_000) { conversations.view.value.command > before }) { "The tap without the window sent nothing" }
    }

    @Test
    fun anIntentsWordsAndDataNeverReachTheField() {
        val before = conversations.view.value.command

        screen.shell(
            "am start -W -n $KESTREL/.app.conversation.screen.ConversationActivity -a android.intent.action.SEND " +
                "-t text/plain --es android.intent.extra.TEXT Reminders",
        )
        screen.shell("am start -W -n $KESTREL/.app.conversation.screen.ConversationActivity -a android.intent.action.VIEW -d kestrel:Reminders")
        Thread.sleep(SETTLE_MILLIS)

        compose.onNodeWithTag(FIELD_TAG).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        assertEquals(before, conversations.view.value.command)
        // The screen keeps the launcher's intent that started it: neither intent, with its words or data, became its own.
        val own = compose.activity.intent
        assertEquals(Intent.ACTION_MAIN, own.action)
        assertNull(own.data)
        assertNull(own.getStringExtra(Intent.EXTRA_TEXT))
    }

    // From Android 14 only accessibility tools may act on the screen's views (design 12.2): the view that holds the field
    // inherits the mark from the window's root, and the node that it gives accessibility services carries it. Android
    // also infers the mark from the root's touch filter, so this checks what the guard gives, not which of its two calls.
    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun theScreensViewsAreForAccessibilityToolsOnly() {
        var view = false
        var node = false
        compose.runOnUiThread {
            val content = compose.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            view = content.isAccessibilityDataSensitive
            node = content.createAccessibilityNodeInfo().isAccessibilityDataSensitive
        }

        assertTrue(view)
        assertTrue(node)
    }

    // Send's centre on the screen, read after the keyboard has settled.
    private fun sendCentre(): Point {
        ScreenKeyboard.settle(compose)
        val bounds = compose.onNodeWithText(TypedTests.text(R.string.conversation_send)).fetchSemanticsNode().boundsInWindow
        val origin = IntArray(2)
        compose.runOnUiThread { compose.activity.window.decorView.getLocationOnScreen(origin) }
        return Point(origin[0] + bounds.center.x.roundToInt(), origin[1] + bounds.center.y.roundToInt())
    }

    // A tap with Android's own input command, as a finger's, which passes through the windows over the screen.
    private fun tap(point: Point) {
        screen.shell("input tap ${point.x} ${point.y}")
    }

    private companion object {
        const val WORDS = "Open my messages"
        const val OVERLAY = "Obscuring overlay"
        const val DEFAULT_MODE = "default"
        const val SETTLE_MILLIS = 1_500L

        /** The app-op's mode in `appops get`, such as "SYSTEM_ALERT_WINDOW: allow; time=+1m ago"; none means default. */
        val OVERLAY_MODE = Regex("""SYSTEM_ALERT_WINDOW: (\w+)""")
    }
}
