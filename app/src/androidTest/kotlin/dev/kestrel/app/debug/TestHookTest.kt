package dev.kestrel.app.debug

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.KestrelApplication
import dev.kestrel.app.SettingKeys
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.screen.model.ScreenJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TestHookTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph = (context.applicationContext as KestrelApplication).graph

    @Test
    fun adbReadsTheState() {
        assertEquals("screen=on", answer(hook("STATE")).substringBefore(' '))
    }

    @Test
    fun adbSwitchesTestMode() = runBlocking {
        val original = graph.settings.get(SettingKeys.SAFETY)
        try {
            assertEquals("testMode=off", answer(hook("TEST_MODE", "on" to false)))
            assertFalse(graph.settings.get(SettingKeys.SAFETY).testMode)
            assertEquals("testMode=on", answer(hook("TEST_MODE", "on" to true)))
            assertTrue(graph.settings.get(SettingKeys.SAFETY).testMode)
        } finally {
            graph.settings.set(SettingKeys.SAFETY, original)
        }
    }

    @Test
    fun adbReadsTheScreen() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")

        // The answer is the path of the snapshot's file in Kestrel's data folder, which hook.ps1 reads with run-as.
        val file = File(context.dataDir, answer(hook("SNAPSHOT")))

        try {
            assertTrue(ScreenJson.decode(file.readText()).elements.any { it.label == "SEND" })
        } finally {
            file.delete()
        }
    }

    @Test
    fun adbScrollsAList() = runBlocking {
        screen.open("ScrollActivity", "Scroll fixture")

        assertEquals("done: action", answer(hook("SCROLL", "direction" to "down")))

        screen.awaitElement { it.label == "Row 25" }
        Unit
    }

    @Test
    fun adbAcceptsNamesWithHyphens() {
        // Play/pause changes nothing on the screen while no media plays, so later tests find their fixtures.
        assertEquals("done: global_action", answer(hook("GLOBAL", "action" to "play-pause")))
    }

    @Test
    fun screenCommandsSayWhenTheServiceIsOff() = runBlocking {
        screen.shell("settings delete secure enabled_accessibility_services")
        withTimeout(10_000) { graph.screen.first { it == null } }

        val reply = hook("SNAPSHOT")

        assertEquals("error: Kestrel's accessibility service is off", failure(reply))
    }

    @Test
    fun adbShowsNumbersAndTapsOne() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val send = screen.awaitElement { it.label == "SEND" && it.clickable }
        answer(hook("OVERLAY", "show" to "numbers"))
        val number = screen.session.numberBadges.first { it.key == send.key }.number

        assertEquals("done: action", answer(hook("TAP", "number" to number)))

        screen.awaitElement { it.label == "Clicked Send" }
        Unit
    }

    @Test
    fun theStateNamesTheGridAndScreenHasCountsWithoutText() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        screen.awaitElement { it.label == "SEND" && it.clickable }

        assertTrue(Regex(".* gridSize=none magnification=none keyboard=(shown|hidden) $BUILD").matches(answer(hook("STATE"))))
        answer(hook("OVERLAY", "show" to "grid"))
        try {
            assertTrue(Regex(".* gridSize=\\d+x4 magnification=none keyboard=(shown|hidden) $BUILD").matches(answer(hook("STATE"))))
        } finally {
            answer(hook("OVERLAY", "show" to "none"))
        }
        val send = answer(hook("SCREEN_HAS", "label" to "SEND", "state" to "clickable"))
        val line = Regex("count=[1-9]\\d* number=none bounds=\\d+,\\d+,\\d+,\\d+ first=\\S+ app=\\S+ appTitle=\\S+ windows=\\d+")
        assertTrue(send, line.matches(send))
        assertTrue(answer(hook("SCREEN_HAS", "label" to "no-such-label")).startsWith("count=0 number=none bounds=none first=none "))
        assertTrue(failure(hook("SCREEN_HAS", "state" to "shiny")).startsWith("error: add --es state with some of checked"))

        // field compares the text on the phone; the key holds no space, which the shell command splits at.
        val message = screen.awaitElement { it.viewId?.endsWith(":id/message_field") == true }
        assertTrue(answer(hook("TYPE", "key" to message.key, "text" to "hello")).startsWith("done: "))
        val field = answer(hook("FIELD", "expected" to "hello", "viewId" to "message_field"))
        assertTrue(field, Regex("field=found equal=true length=5 start=\\d+ end=\\d+ password=false").matches(field))
        assertTrue(answer(hook("FIELD", "expected" to "other", "viewId" to "message_field")).startsWith("field=found equal=false length=5 "))
        // A template's {label} is filled on the phone and sent as a tagged transcript (Kestrel is off, so it starts nothing).
        val tag = "t${System.nanoTime()}"
        assertTrue(answer(hook("SCREEN_HAS", "label" to "SEND", "template" to "{label}", "tag" to tag)).endsWith(" sent=$tag"))
        assertTrue(answer(hook("VOICE_STATE", "tag" to tag)).startsWith("tag=$tag role="))
        assertTrue(failure(hook("SCREEN_HAS", "label" to "SEND", "template" to "x", "tag" to "u$tag")).startsWith("error: add {label} to the template"))
    }

    @Test
    fun aFailedCommandSaysWhy() {
        val reply = hook("TYPE", "text" to "hello")

        assertTrue(reply.text, failure(reply).startsWith("error: add --es key"))
    }

    /** Another app, without DUMP, cannot even acquire the hooks' provider. */
    @Test
    fun anotherAppCannotSendCommands() = runBlocking {
        screen.open("HookProbeActivity", "Hook probe")

        screen.awaitElement { it.label == "Probe refused at the acquisition" }
        Unit
    }

    /**
     * Another app that holds DUMP, as SystemUI or a privileged app of the phone's maker does, acquires the provider, and
     * the provider's check of the caller's uid refuses its call through binder: only adb's shell and root may call.
     */
    @Test
    fun anotherAppThatHoldsDumpIsRefusedOnItsCall() = runBlocking {
        screen.shell("pm grant $PROBE $DUMP")
        try {
            screen.open("HookProbeActivity", "Hook probe")

            screen.awaitElement { it.label == "Probe refused on the call" }
        } finally {
            screen.shell("pm revoke $PROBE $DUMP")
        }
        Unit
    }

    /**
     * Kestrel's own app's uid may acquire its own provider, so only the provider's check of each call's caller refuses it:
     * a call in Kestrel's own process is no binder call, so it carries Kestrel's own uid.
     */
    @Test
    fun theHooksRefuseACallFromKestrelsOwnProcess() {
        val hooks = Uri.parse("content://${TestHookProvider.AUTHORITY}")

        assertThrows(SecurityException::class.java) { context.contentResolver.call(hooks, "STATE", null, null) }
    }

    /** Sends a command the way scripts/hook.ps1 does, with content call from the shell. */
    private fun hook(command: String, vararg extras: Pair<String, Any>): HookReply = ShellHooks(screen::shell).call(command, *extras)

    /** The text of a successful answer. */
    private fun answer(reply: HookReply): String {
        assertEquals(reply.text, HookReply.OK, reply.code)
        return reply.text
    }

    /** The text of an error's answer. */
    private fun failure(reply: HookReply): String {
        assertEquals(reply.text, HookReply.FAILED, reply.code)
        return reply.text
    }

    private companion object {
        /** The end of the state's answer: the build's commit and whether its worktree had changes. */
        const val BUILD = "commit=([0-9a-f]{40}|unknown) dirty=(0|1|unknown)"

        /** The fixture app, whose hook probe calls the provider as another app. */
        const val PROBE = "dev.kestrel.testapp"
        const val DUMP = "android.permission.DUMP"
    }
}
