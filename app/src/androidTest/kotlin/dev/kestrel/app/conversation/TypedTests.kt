package dev.kestrel.app.conversation

import androidx.annotation.StringRes
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.agent.port.ModelPort
import dev.kestrel.app.AppGraph
import dev.kestrel.app.R
import dev.kestrel.app.agent.AgentTests
import dev.kestrel.app.conversation.screen.FIELD_TAG
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.conversation.engine.PastDeletion
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.InputIntent
import kotlinx.coroutines.runBlocking
import java.util.UUID

/**
 * The conversation screen's device tests (design 15.2 of sub-project 7; plan 7F): the screen in front, the owner's typed
 * words in its field and its buttons through Compose's test rule, and plan 5A's scripted model as each round's model, so
 * that no test calls a provider. Kestrel's listening stays off unless a test turns it on, because the screen works with
 * listening off. Kestrel's data on kestrel-api37 holds the owner's own conversations, so every request that these tests
 * type ends with this run's [marker], and [finish] deletes only the conversations that the tests made ([own]).
 */
internal object TypedTests {
    val graph: AppGraph get() = AgentTests.graph

    /** This run's mark, at the end of every request that the tests type, so that a test finds its own conversations. */
    val marker: String = "7f-" + UUID.randomUUID().toString().take(8)

    private val made = mutableSetOf<ConversationId>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val RESUMED = Regex("""(?:topResumedActivity=|\bResumedActivity: )ActivityRecord\{[^}]* dev\.kestrel/\.app\.conversation\.screen\.ConversationActivity""")

    fun text(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)

    /** [words] with this run's [marker], as the tests type them. */
    fun marked(words: String): String = "$words $marker"

    /** Notes [id], a conversation that a test made, so that [finish] deletes it. */
    fun own(id: ConversationId) = synchronized(made) { made += id }

    /**
     * No goal and no conversation open, and [model] as the next rounds' model. A conversation that was open before, such as
     * the owner's own, closes into his list and stays there.
     */
    fun prepare(model: ModelPort) {
        endGoal()
        closeConversation()
        graph.roundModels.modelOverride = model
    }

    /** Ends what a test left open, puts back the model and the conversation's timers, and deletes the tests' conversations. */
    fun finish() {
        try {
            endGoal()
            closeConversation()
        } finally {
            graph.roundModels.modelOverride = null
            graph.conversationTimers = null
            deleteOwn()
        }
    }

    /** Whether the conversation screen is Android's resumed activity, in front. */
    fun screenInFront(screen: ScreenServiceRule): Boolean = RESUMED.containsMatchIn(screen.shell("dumpsys activity activities"))

    /** Brings the conversation screen back to the front, as the launcher does, and waits until it is there. */
    fun bringScreen(screen: ScreenServiceRule) {
        screen.shell("am start -W -n dev.kestrel/.app.conversation.screen.ConversationActivity")
        check(VoiceTests.waitUntil(10_000) { screenInFront(screen) }) { "The conversation screen did not come to the front" }
    }

    /**
     * Types [words] with this run's [marker] into the screen's field and taps Send, as the owner does, notes the conversation
     * as the tests' own, and returns the command number that they started.
     */
    fun send(nodes: SemanticsNodeInteractionsProvider, words: String): Int {
        val before = graph.conversations.view.value.command
        nodes.onNodeWithTag(FIELD_TAG).performTextInput(marked(words))
        nodes.onNodeWithText(text(R.string.conversation_send)).performClick()
        check(VoiceTests.waitUntil(5_000) { graph.conversations.view.value.command > before }) { "The typed words started no command" }
        graph.conversations.view.value.id?.let(::own)
        return graph.conversations.view.value.command
    }

    private fun endGoal() {
        val conversations = graph.conversations
        if (conversations.view.value.goal == null) return
        graph.textChannel.submit(graph.textChannel.input(InputIntent.Stop))
        check(VoiceTests.waitUntil(30_000) { conversations.view.value.goal == null }) { "The goal did not end" }
    }

    private fun closeConversation() {
        val conversations = graph.conversations
        if (conversations.view.value.id == null) return
        graph.textChannel.submit(graph.textChannel.input(InputIntent.NewConversation))
        check(VoiceTests.waitUntil(10_000) { conversations.view.value.id == null }) { "The conversation did not close" }
    }

    // A conversation that is still open, because a test failed before it closed, stays noted for the next finish.
    private fun deleteOwn() {
        val ids = synchronized(made) { made.toList().also { made.clear() } }
        val open = runBlocking { ids.filter { graph.conversations.deletePast(it) != PastDeletion.DELETED } }
        synchronized(made) { made += open }
    }
}
