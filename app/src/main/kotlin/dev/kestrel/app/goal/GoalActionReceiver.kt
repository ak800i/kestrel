package dev.kestrel.app.goal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.kestrel.app.KestrelApplication
import dev.kestrel.app.conversation.InputIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Receives the goal notification's Stop, Continue and Cancel (design 10.6 of sub-project 7): each becomes an owner's input
 * of the conversation (design 6.4), with the next number of his inputs, only for the goal that its notice showed and only
 * while that goal's notice offers what the button does (`GoalButtons`). A tap on the held goal's notification that Kestrel
 * posted without the service can start Kestrel's process; the receiver keeps the broadcast alive (`goAsync`) until the
 * conversation is restored, at most `GoalButtons.RESTORE_WAIT_MILLIS`, and decides against the restored goal
 * (`GoalButtons.press`), so the tap is not lost and the broadcast never outlives Android's limit. It is not
 * exported, so only Kestrel's own PendingIntents reach it, as the listening notification's receiver (spec 8.6).
 */
class GoalActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = (context.applicationContext as KestrelApplication).graph
        val conversations = graph.conversations
        val action = intent.action
        val goal = GoalNotification.goalOf(intent)
        // The tap is the owner's act of its moment: its number and time are taken as it arrives, before the wait for the
        // restore, so that an input that he gives during that wait comes after it (plan 7E). A tap that does not count
        // leaves its number unused, which no other input needs.
        val number = InputIds.next()
        val atMillis = System.currentTimeMillis()
        val pending = goAsync()
        graph.scope.launch {
            try {
                val restored = suspend {
                    conversations.awaitRestored()
                    conversations.view.value
                }
                GoalButtons.press(action, goal, restored, { number }, { atMillis }, graph::report)?.let(conversations::submit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                graph.report("A goal notification's button", e)
            } finally {
                pending.finish()
            }
        }
    }
}
