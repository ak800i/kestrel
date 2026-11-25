package dev.kestrel.app.conversation

import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.AppGraph
import dev.kestrel.app.KestrelApplication
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.machine.QuietClock
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.InputIntent
import dev.kestrel.conversation.model.OwnerInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Puts the conversation that the device keeps in `kestrel.db` into a known state for a device test (design 6.7 of
 * sub-project 7), through the engine's own inputs and view: a conversation that an earlier test left open could otherwise
 * take a transcript away from the instant commands, through the follow-up window, "Continue the goal?" or a goal's
 * report that waited for the owner (whole-plan review of plan 7D, M5). Every wait follows the engine's view.
 */
internal object ConversationTests {
    private const val WORK_MILLIS = 30_000L
    private const val CLOSE_MILLIS = 5_000L

    private val graph: AppGraph
        get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KestrelApplication).graph

    /**
     * Stops whatever "stop" stops (design 6.4 of sub-project 7): the goal in any phase, Kestrel's own request and its
     * question, and waits until none is left and the quiet time no longer waits for a reply or for work.
     */
    fun endWork() {
        val conversations = graph.conversations
        if (stoppable(conversations.view.value)) {
            conversations.submit(OwnerInput(InputIds.next(), InputChannel.VOICE, InputIntent.Stop, System.currentTimeMillis()))
        }
        await(WORK_MILLIS, "The conversation's work did not end") { !stoppable(it) && it.quiet !is QuietClock.Held }
    }

    /**
     * Ends the work, closes the open conversation at once, as its quiet time's end would, and waits until no conversation
     * is open and nothing waits for the owner, so that no reply of an earlier test can open a follow-up window.
     */
    fun close() {
        endWork()
        val conversations = graph.conversations
        if (conversations.view.value.id != null) conversations.endQuietTime()
        await(CLOSE_MILLIS, "The conversation did not close") { it.id == null }
        await(CLOSE_MILLIS, "A report is kept for an owner whom the conversation does not count as present (unlocked, no call)") {
            !it.keptForOwner
        }
    }

    private fun stoppable(view: ConversationView): Boolean = view.goal != null || view.ownRequest || view.question != null

    private fun await(millis: Long, failure: String, done: (ConversationView) -> Boolean) {
        val view = graph.conversations.view
        val reached = runBlocking { withTimeoutOrNull(millis) { view.first(done) } }
        checkNotNull(reached) { "$failure: ${view.value}" }
    }
}
