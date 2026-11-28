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
        val aliases = graph.goalEnvironment.selfChatAliases
        try {
            val refused = hook("AGENT_SETTINGS", "pin" to "4711")
            assertEquals("error: agent-settings has no key pin", failure(refused))
            assertFalse(refused.text.contains("4711"))

            val reply = answer(hook("AGENT_SETTINGS", "ownerEmails" to "owner@example.com", "whatsappOwnName" to "Test", "selfChatAliases" to "$FIXTURES=${SelfChats.TELEGRAM}"))

            assertEquals("ownerEmails=1 viberSelfChatName=unset whatsappOwnName=set selfChatAliases=1", reply)
            assertEquals(listOf("owner@example.com"), graph.settings.get(SettingKeys.SAFETY).ownerEmails)
            assertEquals(mapOf(FIXTURES to SelfChats.TELEGRAM), graph.goalEnvironment.selfChatAliases)
        } finally {
            graph.settings.set(SettingKeys.SAFETY, safety)
            graph.goalEnvironment.selfChatAliases = aliases
        }
    }

    @Test
    fun agentSettingsChangesNothingWhenTheAliasesAreInvalid() = runBlocking {
        val safety = graph.settings.get(SettingKeys.SAFETY)
        val aliases = graph.goalEnvironment.selfChatAliases
        try {
            val refused = hook("AGENT_SETTINGS", "ownerEmails" to "changed@example.com", "selfChatAliases" to FIXTURES)

            assertEquals("error: add --es selfChatAliases as pkg=pkg,pkg=pkg", failure(refused))
            assertEquals(safety, graph.settings.get(SettingKeys.SAFETY))
            assertEquals(aliases, graph.goalEnvironment.selfChatAliases)
        } finally {
            graph.settings.set(SettingKeys.SAFETY, safety)
            graph.goalEnvironment.selfChatAliases = aliases
        }
    }

    @Test
    fun theScriptedModelComesOnlyFromKestrelsFilesAndIsCleared() {
        val folder = File(context.filesDir, AgentTaskHooks.SCRIPTS).apply { mkdirs() }
        val file = File(folder, "hooks-test.json").apply { writeText("""[{"tool": "fail", "arguments": {"reason": "I stop here."}}]""") }
        try {
            // A check reads the script by the scripted model's rules and installs nothing (plan 7G).
            graph.roundModels.modelOverride = null
            assertEquals("script=valid replies=1", answer(hook("AGENT_SCRIPT", "file" to "hooks-test.json", "check" to true)))
            assertNull(graph.roundModels.modelOverride)
            assertEquals("script=installed replies=1", answer(hook("AGENT_SCRIPT", "file" to "hooks-test.json")))
            assertNotNull(graph.roundModels.modelOverride)
            assertTrue(failure(hook("AGENT_SCRIPT", "file" to "../hooks-test.json")).startsWith("error: add --es file"))

            assertEquals("script=none", answer(hook("AGENT_SCRIPT", "clear" to true)))
            assertNull(graph.roundModels.modelOverride)
        } finally {
            graph.roundModels.modelOverride = null
            file.delete()
        }
    }

    @Test
    fun selfchatCheckAndTheTreeReadTheFixtureWithoutItsTexts() = runBlocking {
        val aliases = graph.goalEnvironment.selfChatAliases
        graph.goalEnvironment.selfChatAliases = mapOf(FIXTURES to SelfChats.TELEGRAM)
        val tree = File(context.cacheDir, AgentTaskHooks.TREE_FILE)
        try {
            screen.open("MessengerActivity", "Saved Messages", "--es chat saved")
            assertEquals("selfchat=yes signs=telegram-title,telegram-toolbar", answer(hook("SELFCHAT_CHECK")))
            screen.open("MessengerActivity", "Ana Petrović", "--es chat ana")
            assertEquals("selfchat=no signs=none", answer(hook("SELFCHAT_CHECK")))

            val written = answer(hook("AGENT_TREE"))

            assertTrue(Regex("tree=written path=cache/agent-tree\\.json windows=\\d+ elements=\\d+").matches(written))
            val json = tree.readText()
            assertTrue(json.contains("labelLength"))
            assertFalse(json.contains("Petrovi") || json.contains("See you"))
        } finally {
            graph.goalEnvironment.selfChatAliases = aliases
            tree.delete()
        }
    }

    @Test
    fun screenTapTapsByTheLabelAndRefusesKestrelsOwnApp() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")

        assertEquals("tapped=true count=1", answer(hook("SCREEN_TAP", "label" to "SEND")))
        screen.awaitElement { it.label == "Clicked Send" }
        assertEquals("tapped=false count=0", answer(hook("SCREEN_TAP", "label" to "no-such-label")))

        screen.shell("am start -W -n $KESTREL/.app.MainActivity")
        screen.awaitElement { it.packageName == KESTREL }
        assertEquals("tapped=false error=refused", answer(hook("SCREEN_TAP", "label" to "Back")))
    }

    @Test
    fun screenTapTakesAPartOfALabelALongPressAndAPackageThatMustBeInFront() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")

        assertEquals("tapped=false error=elsewhere", answer(hook("SCREEN_TAP", "label" to "SEND", "package" to "com.google.android.gm")))
        assertEquals("tapped=true count=1", answer(hook("SCREEN_TAP", "contains" to "SEN", "long" to true, "package" to FIXTURES)))
        screen.awaitElement { it.label == "Long-pressed Send" }
        assertEquals("error: add --es label <text> or --es contains <text>", failure(hook("SCREEN_TAP", "package" to FIXTURES)))
    }

    @Test
    fun networkAllowlistNamesTheModelHostsAndOnlyHostNames() = runBlocking {
        val reply = answer(hook("NETWORK_ALLOWLIST"))

        assertTrue(reply, Regex("^hosts=(none|[a-z0-9.,*-]+) modelHosts=[a-z0-9.,*-]+$").matches(reply))
        assertTrue(reply, reply.contains("huggingface.co"))
    }

    @Test
    fun screenTapRefusesACallControlInAnyAppWithoutNamingIt() = runBlocking {
        screen.shell("run-as $FIXTURES rm -f files/presses.log")
        try {
            screen.open("MessengerActivity", "Ana Petrović", "--es chat ana")

            val reply = answer(hook("SCREEN_TAP", "label" to "Call"))

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
            val touch = answer(hook("AGENT_TOUCH_SOURCE", "rule" to "CREDITS", "branch" to "B"))

            assertTrue(touch, touch.startsWith("rule=CREDITS branch=B sdk="))
            assertEquals(TouchSource(TouchRule.CREDITS, TouchBranch.B), graph.screen.value?.touchSource)
            assertEquals(
                "anthropicMarks=on responsesExplicit=on chatCopilotMarks=off",
                answer(hook("AGENT_CACHE", "field" to "chatCopilotMarks", "on" to false)),
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
            assertTrue(answer(hook("AGENT_MEMORY", "add" to true, "key" to "hooks-test", "text" to "Saved")).endsWith(" added=hooks-test"))
            assertTrue(graph.memory.facts().any { it.key == "hooks-test" })

            answer(hook("AGENT_MEMORY", "remove" to true, "key" to "hooks-test"))

            assertFalse(graph.memory.facts().any { it.key == "hooks-test" })
        } finally {
            graph.memory.deleteFact("hooks-test")
        }
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
}
