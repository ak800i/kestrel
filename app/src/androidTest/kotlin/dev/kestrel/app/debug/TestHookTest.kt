package dev.kestrel.app.debug

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
            assertEquals("testMode=off", answer(hook("TEST_MODE --ez on false")))
            assertFalse(graph.settings.get(SettingKeys.SAFETY).testMode)
            assertEquals("testMode=on", answer(hook("TEST_MODE --ez on true")))
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

        assertEquals("done: action", answer(hook("SCROLL --es direction down")))

        screen.awaitElement { it.label == "Row 25" }
        Unit
    }

    @Test
    fun adbAcceptsNamesWithHyphens() {
        // Play/pause changes nothing on the screen while no media plays, so later tests find their fixtures.
        assertEquals("done: global_action", answer(hook("GLOBAL --es action play-pause")))
    }

    @Test
    fun screenCommandsSayWhenTheServiceIsOff() = runBlocking {
        screen.shell("settings delete secure enabled_accessibility_services")
        withTimeout(10_000) { graph.screen.first { it == null } }

        val reply = hook("SNAPSHOT")

        assertTrue(reply, reply.contains("result=1, data=\"error: Kestrel's accessibility service is off\""))
    }

    @Test
    fun adbShowsNumbersAndTapsOne() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val send = screen.awaitElement { it.label == "SEND" && it.clickable }
        answer(hook("OVERLAY --es show numbers"))
        val number = screen.session.numberBadges.first { it.key == send.key }.number

        assertEquals("done: action", answer(hook("TAP --ei number $number")))

        screen.awaitElement { it.label == "Clicked Send" }
        Unit
    }

    @Test
    fun theStateNamesTheGridAndScreenHasCountsWithoutText() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        screen.awaitElement { it.label == "SEND" && it.clickable }

        assertTrue(Regex(".* gridSize=none magnification=none keyboard=(shown|hidden) $BUILD").matches(answer(hook("STATE"))))
        answer(hook("OVERLAY --es show grid"))
        try {
            assertTrue(Regex(".* gridSize=\\d+x4 magnification=none keyboard=(shown|hidden) $BUILD").matches(answer(hook("STATE"))))
        } finally {
            answer(hook("OVERLAY --es show none"))
        }
        val send = answer(hook("SCREEN_HAS --es label SEND --es state clickable"))
        val line = Regex("count=[1-9]\\d* number=none bounds=\\d+,\\d+,\\d+,\\d+ first=\\S+ app=\\S+ appTitle=\\S+ windows=\\d+")
        assertTrue(send, line.matches(send))
        assertTrue(answer(hook("SCREEN_HAS --es label no-such-label")).startsWith("count=0 number=none bounds=none first=none "))
        assertTrue(hook("SCREEN_HAS --es state shiny").contains("result=1, data=\"error: add --es state with some of checked"))

        // field compares the text on the phone; the key holds no space, which the shell command splits at.
        val message = screen.awaitElement { it.viewId?.endsWith(":id/message_field") == true }
        assertTrue(answer(hook("TYPE --es key ${message.key} --es text hello")).startsWith("done: "))
        val field = answer(hook("FIELD --es expected hello --es viewId message_field"))
        assertTrue(field, Regex("field=found equal=true length=5 start=\\d+ end=\\d+ password=false").matches(field))
        assertTrue(answer(hook("FIELD --es expected other --es viewId message_field")).startsWith("field=found equal=false length=5 "))
        // A template's {label} is filled on the phone and sent as a tagged transcript (Kestrel is off, so it starts nothing).
        val tag = "t${System.nanoTime()}"
        assertTrue(answer(hook("SCREEN_HAS --es label SEND --es template {label} --es tag $tag")).endsWith(" sent=$tag"))
        assertTrue(answer(hook("VOICE_STATE --es tag $tag")).startsWith("tag=$tag role="))
        assertTrue(hook("SCREEN_HAS --es label SEND --es template x --es tag u$tag").contains("result=1, data=\"error: add {label} to the template"))
    }

    @Test
    fun aFailedCommandSaysWhy() {
        val reply = hook("TYPE --es text hello")

        assertTrue(reply, reply.contains("result=1, data=\"error: add --es key"))
    }

    @Test
    fun anotherAppCannotSendCommands() = runBlocking {
        screen.open("HookProbeActivity", "Hook probe")

        screen.awaitElement { it.label == "Probe refused" }
        Unit
    }

    /** Sends a command the way scripts/hook.ps1 does. */
    private fun hook(command: String): String =
        screen.shell("am broadcast -n ${ScreenServiceRule.KESTREL}/dev.kestrel.app.debug.TestHookReceiver -a dev.kestrel.hook.$command")

    /** The data of a successful answer. */
    private fun answer(reply: String): String {
        assertTrue(reply, reply.contains("result=-1, data=\""))
        return reply.substringAfter("data=\"").substringBeforeLast('"')
    }

    private companion object {
        /** The end of the state's answer: the build's commit and whether its worktree had changes. */
        const val BUILD = "commit=([0-9a-f]{40}|unknown) dirty=(0|1|unknown)"
    }
}
