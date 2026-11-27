package dev.kestrel.eval.run

import dev.kestrel.eval.device.Clock
import dev.kestrel.eval.device.waitUntil
import dev.kestrel.eval.hook.HookAnswer
import dev.kestrel.eval.hook.HookLost
import dev.kestrel.eval.hook.Hooks
import dev.kestrel.eval.task.RunScope
import dev.kestrel.eval.task.Setup
import dev.kestrel.eval.task.SetupFailed
import dev.kestrel.eval.task.SetupResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Kestrel's conversation around a run (design 6.4, 14.1 and 14.4 of sub-project 7), read in counts and kinds only. Each
 * run starts with no conversation open ([close]), so that a task of one command is a conversation of one command, as a
 * task was before sub-project 7, and no earlier run's records, owner's words or allowance reach the run's commands. No run
 * leaves a goal open for the next one ([stopGoal]), because a command at a held goal resumes it, and none leaves its own
 * conversations among the owner's past conversations ([deleteRun]).
 */
class ConversationGuard(private val hooks: Hooks, private val clock: Clock) {
    /**
     * Closes the open conversation, if any. Its quiet clock runs once its final reply has ended, and conversation-control
     * do=expire then ends the quiet time at once, so the guard reads the conversation once a second and ends the quiet time
     * while it runs, until the conversation is closed, for at most [within] by the clock, however slow adb is ([waitUntil]).
     * A goal that is open is no run's to end, so it fails the step ([SetupFailed]), and so does a conversation that stays
     * open. A read whose answer is lost is read again a second later.
     */
    fun close(within: Duration = CLOSE) {
        val closed = clock.waitUntil(within, POLL) { deadline ->
            val hooks = hooks.until(deadline)
            val state = quietly { hooks.conversationState() } ?: return@waitUntil false
            if (state["open"] != "yes") return@waitUntil true
            if (goalOpen(state)) throw SetupFailed("Kestrel's conversation has a goal open (${state["goal"]}); say 'Kestrel, stop' first.")
            if (state["quiet"] == "running") expire(hooks)
            false
        }
        if (!closed) throw SetupFailed("Kestrel's conversation did not close within ${within.inWholeSeconds} s.")
    }

    /**
     * After a run: closes the run's conversation ([close]) and deletes the hooks' own conversations (plan 7F's delete-typed,
     * which covers every input that a hook gives, tagged or not), so that no test conversation stays among the owner's past
     * conversations and their search. Kestrel deletes only a conversation that a hook's input opened and that no input of
     * the owner's with his words or his choice has reached, never one of the owner's, and keeps its marks across a restart.
     * A conversation that does not close within [CLOSE], as after a long final reply, only gives [warn] a warning:
     * delete-typed keeps it marked, and the next run's restore or the series' cleanup ([deleteLeft]) deletes it. A deletion
     * that failed fails the restore that calls it.
     */
    fun deleteRun(warn: (String) -> Unit) {
        try {
            close()
        } catch (e: SetupFailed) {
            warn("${e.message} It stays among the hooks' own conversations, which the next run or the series' cleanup deletes.")
        }
        delete()
    }

    /**
     * The series' cleanup after its last run ([Preflight] pushes it as a restore): closes the open conversation, waiting up
     * to [CLEANUP] for a long final reply, and deletes every one of the hooks' own conversations. One that stays open, or
     * whose deletion failed, fails it, and the console says what to do by hand.
     */
    fun deleteLeft() {
        close(CLEANUP)
        delete()
    }

    private fun delete() {
        val answer = hooks.deleteTyped()
        checkNotNull(answer["deleted"]) { "Kestrel answered conversation-control do=delete-typed without its counts." }
        check(answer.int("failed") == 0) { "Kestrel could not delete ${answer.int("failed")} of the hooks' own conversations." }
    }

    /**
     * Ends the goal that is open, held or not, with [stop], the owner's "stop" (design 6.4), and waits up to [STOP] by the
     * clock for its end. Returns whether no goal is open then; without a goal it does nothing, and when Kestrel does not
     * answer it returns false.
     */
    fun stopGoal(stop: () -> Unit): Boolean {
        val state = quietly { hooks.conversationState() } ?: return false
        if (!goalOpen(state)) return true
        stop()
        return clock.waitUntil(STOP, POLL) { deadline ->
            quietly { hooks.until(deadline).conversationState() }?.let { !goalOpen(it) } ?: false
        }
    }

    // An expire whose answer was lost may have reached Kestrel; a second one changes nothing, so the next read decides.
    private fun expire(hooks: Hooks) {
        try {
            hooks.conversationControl("expire")
        } catch (e: HookLost) {
            // Read back by the next conversation-state.
        }
    }

    private fun goalOpen(state: HookAnswer): Boolean = state["goal"].let { it != null && it != "none" }

    companion object {
        val CLOSE = 60.seconds
        val CLEANUP = 5.minutes
        val STOP = 15.seconds
        private val POLL = 1.seconds
    }
}

/**
 * A run's last setup step (design 14.1 of sub-project 7): the open conversation closed, so that the run's first command
 * opens one. It comes after the task's own steps, so that a run that they find untestable touches no conversation; its
 * restore, which runs after the run's stops and before the task's restores, closes the run's conversation and deletes the
 * hooks' own conversations ([ConversationGuard.deleteRun]).
 */
class NewConversation(private val conversations: ConversationGuard) : Setup {
    override fun apply(scope: RunScope): SetupResult {
        conversations.close()
        scope.restores.push("the run's conversations closed and deleted", "Kestrel > Past conversations: delete the conversations of the run's commands.") {
            conversations.deleteRun { scope.console.warn(it) }
        }
        return SetupResult.Ready
    }
}
