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
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Kestrel's conversation around a run (design 6.4, 14.1 and 14.4 of sub-project 7), read in counts and kinds only. Each
 * run starts with no conversation open ([close]), so that a task of one command is a conversation of one command, as a
 * task was before sub-project 7, and no earlier run's records, owner's words or allowance reach the run's commands. No run
 * leaves a goal open for the next one ([stopGoal]), because a command at a held goal resumes it, and none leaves its own
 * conversations among the owner's past conversations ([deleteRun]). One guard serves a whole series: it knows the commands
 * that the series' runs gave Kestrel ([gave]), so that the series' cleanup ([deleteLeft]) ends only what they opened.
 *
 * One rule holds for every close (plan 7G): the evaluation ends only a conversation that carries the hooks' mark, which
 * every round of a wait reads (conversation-state's `typed=`) and which Kestrel checks again on the conversation's loop
 * when it ends the quiet time (conversation-control do=expire-typed). A conversation without the mark is the owner's, also
 * one that he continued with his words or opened himself during a wait, and the evaluation never ends it.
 */
class ConversationGuard(private val hooks: Hooks, private val clock: Clock) {
    // The tags of the commands that the series' runs gave Kestrel; the shutdown hook's thread reads them too.
    private val given: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** A run of the series gives Kestrel the command of [tag], which may open a conversation of the series' own. */
    fun gave(tag: String) {
        given += tag
    }

    /**
     * Leaves no conversation open, before a run's first command ([NewConversation]) and between its turns (the end of the
     * quiet time): the hooks' own conversation is closed through Kestrel's check of their mark ([expire]) once its quiet
     * clock runs, while the owner's, which has no mark, is waited for until its own quiet time has closed it, and is never
     * ended. The guard reads the conversation once a second for at most [within] by the clock, however slow adb is
     * ([waitUntil]). A goal that is open is no run's to end, so it fails the step ([SetupFailed]), and so does a conversation
     * that stays open. A read whose answer is lost is read again a second later.
     */
    fun close(within: Duration = CLOSE) = closing(within, Wait.NONE_OPEN)

    /**
     * Closes the hooks' own conversation, for a run's restore ([deleteRun]) and the series' cleanup ([deleteLeft]): the wait
     * is over once no conversation is open or the open one has no mark, because that one is the owner's and stays open, also
     * when the owner continued the hooks' conversation with his words during the wait, or opened his own after it closed. A
     * goal in the hooks' conversation fails it, and so does a conversation of theirs that stays open for [within].
     */
    private fun closeOwn(within: Duration) = closing(within, Wait.NONE_OF_THEIRS)

    // The wait of [close] and [closeOwn], one round a second for at most [within] by the clock.
    private fun closing(within: Duration, wait: Wait) {
        var owners = false
        val closed = clock.waitUntil(within, POLL) { deadline ->
            val hooks = hooks.until(deadline)
            val state = quietly { hooks.conversationState() } ?: return@waitUntil false
            if (state["open"] != "yes") return@waitUntil true
            val theirs = state["typed"] == "yes"
            if (!theirs && wait == Wait.NONE_OF_THEIRS) return@waitUntil true
            owners = !theirs
            if (goalOpen(state)) {
                throw SetupFailed(
                    if (theirs) {
                        "Kestrel's conversation has a goal open (${state["goal"]}); say 'Kestrel, stop' first."
                    } else {
                        "The owner's conversation has a goal open (${state["goal"]}), which the evaluation never ends."
                    },
                )
            }
            if (theirs && state["quiet"] == "running") expire(hooks)
            false
        }
        if (closed) return
        throw SetupFailed(
            if (owners) {
                "The owner's conversation did not close by itself within ${within.inWholeSeconds} s; the evaluation never ends it."
            } else {
                "Kestrel's conversation did not close within ${within.inWholeSeconds} s."
            },
        )
    }

    /**
     * After a run: closes the run's conversation while it carries the hooks' mark ([closeOwn]) and deletes the hooks' own conversations (plan 7F's delete-typed,
     * which covers every input that a hook gives, tagged or not), so that no test conversation stays among the owner's past
     * conversations and their search. Kestrel deletes only a conversation that a hook's input opened and that no input of
     * the owner's with his words or his choice has reached, never one of the owner's, and keeps its marks across a restart.
     * A conversation that does not close within [CLOSE], as after a long final reply, only gives [warn] a warning:
     * delete-typed keeps it marked, and the next run's restore or the series' cleanup ([deleteLeft]) deletes it. A deletion
     * that failed fails the restore that calls it.
     */
    fun deleteRun(warn: (String) -> Unit) {
        try {
            closeOwn(CLOSE)
        } catch (e: SetupFailed) {
            warn("${e.message} It stays among the hooks' own conversations, which the next run or the series' cleanup deletes.")
        }
        delete()
    }

    /**
     * The series' cleanup after its last run ([Preflight] pushes it as a restore), which ends only what the series' runs
     * opened, never the owner's conversation or goal. A series whose runs gave Kestrel no command, as when every run was
     * untestable, opened nothing, so the cleanup sends nothing. Otherwise delete-typed deletes the hooks' own conversations
     * that are closed and counts the one that is open (`open=`). Only while one is, the cleanup closes the open conversation
     * as long as it carries the hooks' mark, checked on every round of its wait and by Kestrel itself when it ends the quiet
     * time ([closeOwn]), waiting up to [CLEANUP] for a long final reply, and deletes it then. An open conversation without
     * the mark is the owner's and stays open. A conversation of the hooks' that stays open, or a deletion that failed, fails
     * the cleanup, and the console says what to do by hand.
     */
    fun deleteLeft() {
        if (given.isEmpty()) return
        if (delete().int("open") == 0) return
        closeOwn(CLEANUP)
        delete()
    }

    private fun delete(): HookAnswer {
        val answer = hooks.deleteTyped()
        checkNotNull(answer["deleted"]) { "Kestrel answered conversation-control do=delete-typed without its counts." }
        check(answer.int("failed") == 0) { "Kestrel could not delete ${answer.int("failed")} of the hooks' own conversations." }
        return answer
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

    // The end of the quiet time of the hooks' own conversation only: Kestrel checks their mark on the conversation's loop,
    // at the moment that it ends the quiet time, and refuses (`expire=not-typed`) without it. An expire whose answer was
    // lost may have reached Kestrel; a second one changes nothing, so the next read decides, as it does after a refusal.
    private fun expire(hooks: Hooks) {
        try {
            hooks.conversationControl("expire-typed")
        } catch (e: HookLost) {
            // Read back by the next conversation-state.
        }
    }

    // When a wait is over: once no conversation is open, or once none of the hooks' own is.
    private enum class Wait { NONE_OPEN, NONE_OF_THEIRS }

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
