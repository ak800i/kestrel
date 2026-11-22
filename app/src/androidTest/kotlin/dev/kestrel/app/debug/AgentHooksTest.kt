package dev.kestrel.app.debug

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.KestrelApplication
import dev.kestrel.app.SettingKeys
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.app.screen.ScreenServiceRule.Companion.KESTREL
import dev.kestrel.safety.SelfChats
import dev.kestrel.screen.act.TouchBranch
import dev.kestrel.screen.act.TouchRule
import dev.kestrel.screen.act.TouchSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The agent's debug hooks of plan 5B (design D28 of sub-project 5), sent as scripts\hook.ps1 sends them. */
@RunWith(AndroidJUnit4::class)
class AgentHooksTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph = (context.applicationContext as KestrelApplication).graph

    @Test
    fun agentSettingsStoresTestModesNamesAndRefusesAnyOtherKeyWithoutItsValue() = runBlocking {
        val safety = graph.settings.get(SettingKeys.SAFETY)
        val aliases = graph.agent.selfChatAliases
        try {
            val refused = hook("AGENT_SETTINGS --es pin 4711")
            assertTrue(refused.contains("result=1, data=\"error: agent-settings has no key pin\""))
            assertFalse(refused.contains("4711"))

            val reply = answer(hook("AGENT_SETTINGS --es ownerEmails owner@example.com --es whatsappOwnName Test --es selfChatAliases $FIXTURES=${SelfChats.TELEGRAM}"))

            assertEquals("ownerEmails=1 viberSelfChatName=unset whatsappOwnName=set selfChatAliases=1", reply)
            assertEquals(listOf("owner@example.com"), graph.settings.get(SettingKeys.SAFETY).ownerEmails)
            assertEquals(mapOf(FIXTURES to SelfChats.TELEGRAM), graph.agent.selfChatAliases)
        } finally {
            graph.settings.set(SettingKeys.SAFETY, safety)
            graph.agent.selfChatAliases = aliases
        }
    }

    @Test
    fun theScriptedModelComesOnlyFromKestrelsFilesAndIsCleared() {
        val folder = File(context.filesDir, AgentTaskHooks.SCRIPTS).apply { mkdirs() }
        val file = File(folder, "hooks-test.json").apply { writeText("""[{"tool": "fail", "arguments": {"reason": "I stop here."}}]""") }
        try {
            assertEquals("script=installed replies=1", answer(hook("AGENT_SCRIPT --es file hooks-test.json")))
            assertNotNull(graph.agent.modelOverride)
            assertTrue(hook("AGENT_SCRIPT --es file ../hooks-test.json").contains("result=1, data=\"error: add --es file"))

            assertEquals("script=none", answer(hook("AGENT_SCRIPT --ez clear true")))
            assertNull(graph.agent.modelOverride)
        } finally {
            graph.agent.modelOverride = null
            file.delete()
        }
    }

    @Test
    fun selfchatCheckAndTheTreeReadTheFixtureWithoutItsTexts() = runBlocking {
        val aliases = graph.agent.selfChatAliases
        graph.agent.selfChatAliases = mapOf(FIXTURES to SelfChats.TELEGRAM)
        val tree = File(context.cacheDir, AgentTaskHooks.TREE_FILE)
        try {
            screen.open("MessengerActivity", "Saved Messages", "--es chat saved")
            assertEquals("selfchat=yes signs=telegram-title,telegram-subtitle", answer(hook("SELFCHAT_CHECK")))
            screen.open("MessengerActivity", "Ana Petrović", "--es chat ana")
            assertEquals("selfchat=no signs=none", answer(hook("SELFCHAT_CHECK")))

            val written = answer(hook("AGENT_TREE"))

            assertTrue(Regex("tree=written path=cache/agent-tree\\.json windows=\\d+ elements=\\d+").matches(written))
            val json = tree.readText()
            assertTrue(json.contains("labelLength"))
            assertFalse(json.contains("Petrovi") || json.contains("See you"))
        } finally {
            graph.agent.selfChatAliases = aliases
            tree.delete()
        }
    }

    @Test
    fun screenTapTapsByTheLabelAndRefusesKestrelsOwnApp() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")

        assertEquals("tapped=true count=1", answer(hook("SCREEN_TAP --es label SEND")))
        screen.awaitElement { it.label == "Clicked Send" }
        assertEquals("tapped=false count=0", answer(hook("SCREEN_TAP --es label no-such-label")))

        screen.shell("am start -W -n $KESTREL/.app.MainActivity")
        screen.awaitElement { it.packageName == KESTREL }
        assertEquals("tapped=false error=refused", answer(hook("SCREEN_TAP --es label Back")))
    }

    @Test
    fun screenTapRefusesACallControlInAnyAppWithoutNamingIt() = runBlocking {
        screen.shell("run-as $FIXTURES rm -f files/presses.log")
        try {
            screen.open("MessengerActivity", "Ana Petrović", "--es chat ana")

            val reply = answer(hook("SCREEN_TAP --es label Call"))

            assertEquals("tapped=false error=refused", reply)
            assertEquals("", screen.shell("run-as $FIXTURES cat files/presses.log").trim())
        } finally {
            screen.shell("run-as $FIXTURES rm -f files/presses.log")
        }
    }

    @Test
    fun theTouchSourceAndTheCacheFieldsAreStored() = runBlocking {
        val source = graph.settings.get(SettingKeys.TOUCH_RULE)
        val fields = graph.settings.get(SettingKeys.AGENT_CACHE_FIELDS)
        try {
            val touch = answer(hook("AGENT_TOUCH_SOURCE --es rule CREDITS --es branch B"))

            assertTrue(touch, touch.startsWith("rule=CREDITS branch=B sdk="))
            assertEquals(TouchSource(TouchRule.CREDITS, TouchBranch.B), graph.screen.value?.touchSource)
            assertEquals(
                "anthropicMarks=on responsesExplicit=on chatCopilotMarks=off",
                answer(hook("AGENT_CACHE --es field chatCopilotMarks --ez on false")),
            )
        } finally {
            graph.settings.set(SettingKeys.TOUCH_RULE, source)
            graph.screen.value?.touchSource = source
            graph.settings.set(SettingKeys.AGENT_CACHE_FIELDS, fields)
        }
    }

    @Test
    fun agentMemoryAddsAndRemovesATestAliasByItsKey() = runBlocking {
        try {
            assertTrue(answer(hook("AGENT_MEMORY --ez add true --es key hooks-test --es text Saved")).endsWith(" added=hooks-test"))
            assertTrue(graph.memory.facts().any { it.key == "hooks-test" })

            answer(hook("AGENT_MEMORY --ez remove true --es key hooks-test"))

            assertFalse(graph.memory.facts().any { it.key == "hooks-test" })
        } finally {
            graph.memory.deleteFact("hooks-test")
        }
    }

    /** Sends a command the way scripts/hook.ps1 does. */
    private fun hook(command: String): String =
        screen.shell("am broadcast -n $KESTREL/dev.kestrel.app.debug.TestHookReceiver -a dev.kestrel.hook.$command")

    /** The data of a successful answer. */
    private fun answer(reply: String): String {
        assertTrue(reply, reply.contains("result=-1, data=\""))
        return reply.substringAfter("data=\"").substringBeforeLast('"')
    }
}
