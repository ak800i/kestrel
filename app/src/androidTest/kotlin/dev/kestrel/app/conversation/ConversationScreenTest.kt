package dev.kestrel.app.conversation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.agent.EndReason
import dev.kestrel.agent.TaskRequest
import dev.kestrel.agent.testing.ScriptedModel
import dev.kestrel.agent.testing.ScriptedModel.Companion.call
import dev.kestrel.app.R
import dev.kestrel.app.agent.AgentTests
import dev.kestrel.app.conversation.screen.ConversationActivity
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.app.screen.ScreenServiceRule.Companion.KESTREL
import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.conversation.model.InputChannel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The conversation screen on kestrel-api37 (design 10.1, 10.2 and 15.2 of sub-project 7): the launcher opens it and its
 * Settings open the settings app; a typed command leaves Kestrel's screen and works on the app below with listening off;
 * a second typed command gets the first one's record; and "New conversation" closes the conversation into the list. Each test
 * types its words with `TypedTests`' marker and deletes only its own conversations.
 */
@RunWith(AndroidJUnit4::class)
class ConversationScreenTest {
    @get:Rule(order = 0)
    val screen = ScreenServiceRule()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ConversationActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph get() = TypedTests.graph

    private val onIt = call("say", null, null, null, "text" to "On it.")
    private val fail = call("fail", null, null, null, "reason" to "I stop here.")

    @After
    fun stop() {
        try {
            TypedTests.finish()
        } finally {
            screen.shell("am force-stop $FIXTURES")
        }
    }

    @Test
    fun theLauncherOpensTheConversationScreenAndItsSettingsOpenTheSettingsApp() {
        val launcher = checkNotNull(context.packageManager.getLaunchIntentForPackage(KESTREL)) { "Kestrel has no launcher activity" }
        assertEquals(ConversationActivity::class.java.name, launcher.component?.className)

        compose.onNodeWithText(TypedTests.text(R.string.conversation_settings)).performClick()

        val settings = Regex("""(?:topResumedActivity=|\bResumedActivity: )ActivityRecord\{[^}]* dev\.kestrel/\.app\.MainActivity""")
        check(VoiceTests.waitUntil(10_000) { settings.containsMatchIn(screen.shell("dumpsys activity activities")) }) { "The settings did not open" }
        screen.shell("input keyevent KEYCODE_BACK")
        check(VoiceTests.waitUntil(10_000) { TypedTests.screenInFront(screen) }) { "Back did not return to the conversation screen" }
    }

    @Test
    fun aTypedCommandLeavesKestrelsScreenAndWorksOnTheAppBelowWithListeningOff() {
        screen.open("ViewsActivity", "Views fixture")
        TypedTests.bringScreen(screen)
        val model = ScriptedModel(listOf(onIt, fail))
        TypedTests.prepare(model)
        val spoken = graph.offVoice.replies

        val ended = AgentTests.ended(TypedTests.send(compose, "Look at the fixture"))

        assertEquals(EndReason.FAILED_BY_MODEL, ended.report.end)
        // The round's first observation was the fixture's, so the screen had left before the goal looked (design 10.2).
        assertTrue(AgentTests.texts(model).contains("Views fixture"))
        assertFalse(TypedTests.screenInFront(screen))
        assertEquals(InputChannel.TEXT, graph.conversations.view.value.records.single().channel)
        // A typed command's words are written, not spoken (design 11): with the setting off nothing was spoken.
        assertEquals(spoken, graph.offVoice.replies)
    }

    @Test
    fun aSecondTypedCommandGetsTheFirstOnesRecord() {
        screen.open("ViewsActivity", "Views fixture")
        TypedTests.bringScreen(screen)
        val model = ScriptedModel(listOf(onIt, fail, fail))
        TypedTests.prepare(model)

        AgentTests.ended(TypedTests.send(compose, "Look at the fixture"))
        TypedTests.bringScreen(screen)
        compose.onNodeWithText(TypedTests.marked("Look at the fixture")).assertIsDisplayed()
        AgentTests.ended(TypedTests.send(compose, "And look again"))

        val second = model.requests.last().context.orEmpty()
        assertTrue(second.contains(TaskRequest.CONVERSATION_HEADER))
        assertTrue(second.contains(TypedTests.marked("Look at the fixture")))
        assertEquals(2, graph.conversations.view.value.records.size)
    }

    @Test
    fun newConversationClosesTheConversationIntoTheListAndClearsTheScreen() {
        screen.open("ViewsActivity", "Views fixture")
        TypedTests.bringScreen(screen)
        TypedTests.prepare(ScriptedModel(listOf(fail)))
        AgentTests.ended(TypedTests.send(compose, "Look at the fixture"))
        TypedTests.bringScreen(screen)

        compose.onNodeWithText(TypedTests.text(R.string.conversation_new)).performClick()

        check(VoiceTests.waitUntil(10_000) { graph.conversations.view.value.id == null }) { "The conversation did not close" }
        val words = TypedTests.marked("Look at the fixture")
        compose.waitUntil(5_000) { compose.onAllNodesWithText(words).fetchSemanticsNodes().isEmpty() }
        assertTrue(runBlocking { graph.conversationStore.list() }.any { it.firstRequest == words })
    }
}
