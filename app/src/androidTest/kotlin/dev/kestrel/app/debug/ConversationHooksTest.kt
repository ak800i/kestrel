package dev.kestrel.app.debug

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.agent.EndReason
import dev.kestrel.agent.goal.GoalLimits
import dev.kestrel.agent.testing.ScriptedModel
import dev.kestrel.agent.testing.ScriptedModel.Companion.call
import dev.kestrel.app.SettingKeys
import dev.kestrel.app.agent.AgentTests
import dev.kestrel.app.conversation.TypedTests
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.conversation.machine.QuietClock
import dev.kestrel.conversation.model.InputChannel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The conversation's hooks of debug builds (design 14.4 of sub-project 7), called as TestHookReceiver calls them. */
class ConversationHooksTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val conversations = AgentTests.graph.conversations

    @After
    fun stop() {
        AgentTests.stop()
    }

    @Test
    fun theStateCountsACommandAndExpireClosesTheConversation() {
        AgentTests.start()
        val model = ScriptedModel(listOf(call("fail", null, null, null, "reason" to "I stop here.")))
        AgentTests.ended(AgentTests.run("open the settings", model))
        // The quiet time runs once Kestrel has spoken the reply; only then can the hook end it.
        check(VoiceTests.waitUntil(30_000) { conversations.view.value.quiet is QuietClock.Running }) { "The quiet time did not run" }

        val open = hook("CONVERSATION_STATE")
        assertTrue(open, Regex("open=yes continued=no commands=1 goal=none quiet=running quietLeft=\\d+ replyChannel=voice testMode=(on|off)").matches(open))
        assertEquals("expire=sent", hook("CONVERSATION_CONTROL", "do" to "expire"))
        check(VoiceTests.waitUntil(10_000) { conversations.view.value.id == null }) { "The conversation did not close" }
        val closed = hook("CONVERSATION_STATE")
        assertTrue(closed, Regex("open=no continued=no commands=0 goal=none quiet=off quietLeft=none replyChannel=(voice|text) testMode=(on|off)").matches(closed))
    }

    @Test
    fun theLimitsGoBackAndTheControlsCarryNoWords() {
        val settings = AgentTests.graph.settings
        val stored = runBlocking { settings.get(SettingKeys.CONVERSATIONS) }
        val was = ConversationHookAnswers.limitsText(stored.roundLimits)
        try {
            assertEquals("limits=50/5/10 was=$was", hook("CONVERSATION_CONTROL", "do" to "limits"))
            assertEquals(GoalLimits(50, 5, 10), runBlocking { settings.get(SettingKeys.CONVERSATIONS) }.roundLimits)
            assertEquals("limits=$was was=50/5/10", hook("CONVERSATION_CONTROL", "do" to "limits", "limits" to was))
            assertEquals(stored, runBlocking { settings.get(SettingKeys.CONVERSATIONS) })

            val restored = hook("CONVERSATION_CONTROL", "do" to "restore-check")
            assertTrue(restored, Regex("restored=(nothing|open|closed|goal-held|goal-ended)").matches(restored))
            assertEquals("error: add --es do with one of new, expire, limits, restore-check or delete-typed", hook("CONVERSATION_CONTROL", "do" to "other"))
        } finally {
            runBlocking { settings.set(SettingKeys.CONVERSATIONS, stored) }
        }
    }

    // Design 14.4 of sub-project 7: the hook types as the owner does on the conversation screen, and its tag names the command.
    @Test
    fun conversationTypeStartsATypedCommandThatItsTagNames() {
        TypedTests.prepare(ScriptedModel(listOf(call("fail", null, null, null, "reason" to "I stop here."))))
        try {
            val tag = "typed-${System.currentTimeMillis()}"
            assertEquals("typed read=command tag=$tag", hook("CONVERSATION_TYPE", "text" to TypedTests.marked("open the settings"), "tag" to tag))

            check(VoiceTests.waitUntil(10_000) { (VoiceHooks.tags.tagged(tag)?.task ?: 0) > 0 }) { "The tag got no command" }
            conversations.view.value.id?.let(TypedTests::own)
            val command = checkNotNull(VoiceHooks.tags.tagged(tag)).task
            assertEquals(EndReason.FAILED_BY_MODEL, AgentTests.ended(command).report.end)
            assertEquals(InputChannel.TEXT, conversations.view.value.records.single().channel)
            assertEquals("error: the tag $tag is in use; give each transcript a new tag", hook("CONVERSATION_TYPE", "text" to "open the settings", "tag" to tag))
            assertEquals("error: add --es text with the words to type", hook("CONVERSATION_TYPE"))

            // The hooks' own conversation, once closed, is deleted; the owner's are not (plan 7F).
            val id = checkNotNull(conversations.view.value.id)
            hook("CONVERSATION_CONTROL", "do" to "new")
            check(VoiceTests.waitUntil(10_000) { conversations.view.value.id == null }) { "The conversation did not close" }
            assertTrue(hook("CONVERSATION_CONTROL", "do" to "delete-typed").startsWith("deleted="))
            assertTrue(runBlocking { TypedTests.graph.conversationStore.list() }.none { it.id == id })
        } finally {
            TypedTests.finish()
        }
    }

    private fun hook(command: String, vararg extras: Pair<String, String>): String {
        val intent = Intent(TestHooks.ACTION_PREFIX + command)
        for ((key, value) in extras) intent.putExtra(key, value)
        return runBlocking { TestHooks(AgentTests.graph, context).run(intent) }.text
    }
}
