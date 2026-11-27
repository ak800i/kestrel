package dev.kestrel.eval.run

import dev.kestrel.eval.device.Clock
import dev.kestrel.eval.device.waitUntil
import dev.kestrel.eval.hook.HookAnswer
import dev.kestrel.eval.hook.HookError
import dev.kestrel.eval.hook.HookLost
import dev.kestrel.eval.hook.HookSilent
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
 * leaves work running for the next one ([stopWork]): not a goal, because a command at a held goal resumes it, and not
 * Kestrel's own request, such as a switch whose question would take the next command as its answer. None leaves its own
 * conversations among the owner's past conversations ([deleteRun]). One guard serves a whole series: it knows the commands
 * that the series' runs gave Kestrel ([gave]), so that the series' cleanup ([deleteLeft]) ends only what they opened.
 *
 * One rule holds for every close and every stop (plan 7G): the evaluation ends only a conversation, and stops only work (a
 * goal or Kestrel's own request), that carries the hooks' mark, which every round of a wait reads (conversation-state's
 * `typed=`: the open conversation's mark, or while none is open, that of the request that runs) and which Kestrel checks
 * again on the conversation's loop when it ends the quiet time (conversation-control do=expire-typed) or takes the stop
 * (goal-control do=stop-typed). A conversation or a request without the mark is the owner's, also one that he continued or
 * answered with his words or opened himself during a wait, and the evaluation never ends it nor stops its work.
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
     * clock runs, within [within]. The owner's, which has no mark, is never ended: the guard waits until its own quiet time
     * closes it, for as long as Kestrel first reports that the quiet time has left (`quietLeft=`) and [OWNER_MARGIN] more,
     * or [within] when Kestrel reports no time, as while his goal or his own request runs. When his conversation or his
     * request is still open then, the owner is busy with Kestrel, and the series stops ([OwnerBusy]) rather than failing runs
     * that he keeps from running. The guard reads the conversation once a second, measured by the clock however slow adb is
     * ([waitUntil]). Work of the hooks' that still runs, a goal or Kestrel's own request, is no step's to end but the run's
     * restore's ([stopWork]), so it fails the step ([SetupFailed]), and so does a conversation of theirs that stays open. A
     * read whose answer is lost is read again a second later.
     */
    fun close(within: Duration = CLOSE) = closing(within, Wait.NONE_OPEN)

    /**
     * Closes the hooks' own conversation, for a run's restore ([deleteRun]) and the series' cleanup ([deleteLeft]): the wait
     * is over once no conversation is open or the open one has no mark, because that one is the owner's and stays open, also
     * when the owner continued the hooks' conversation with his words during the wait, or opened his own after it closed.
     * Work of the hooks' that still runs fails it, and so does a conversation of theirs that stays open for [within].
     */
    private fun closeOwn(within: Duration) = closing(within, Wait.NONE_OF_THEIRS)

    // The wait of [close] and [closeOwn], one round a second by the clock: for at most [within], or for the owner's quiet
    // time as Kestrel first reports it.
    private fun closing(within: Duration, wait: Wait) {
        var owners = false
        var learned = false
        val closed = clock.waitUntil(within, POLL) { deadline ->
            val hooks = hooks.until(deadline)
            val state = quietly { hooks.conversationState() } ?: return@waitUntil false
            // Kestrel's own request may run while no conversation is open, as a switch that asks before it moves into a
            // past conversation.
            if (state["open"] != "yes" && !requestRuns(state)) return@waitUntil true
            val theirs = state["typed"] == "yes"
            if (!theirs && wait == Wait.NONE_OF_THEIRS) return@waitUntil true
            owners = !theirs
            if (!theirs) {
                val left = state["quietLeft"]?.toLongOrNull()
                if (left != null && !learned) {
                    learned = true
                    deadline.extend(left.seconds + OWNER_MARGIN)
                }
                return@waitUntil false
            }
            work(state)?.let { throw SetupFailed("Kestrel's conversation has $it; say 'Kestrel, stop' first.") }
            if (state["quiet"] == "running") expire(hooks)
            false
        }
        if (closed) return
        if (owners) {
            throw OwnerBusy(
                "The owner's conversation with Kestrel stayed open past its quiet time, and the evaluation never ends it (owner-busy); " +
                    "the series stops, and --resume runs the rest once he is done.",
            )
        }
        throw SetupFailed("Kestrel's conversation did not close within ${within.inWholeSeconds} s.")
    }

    /**
     * After a run: closes the run's conversation while it carries the hooks' mark ([closeOwn]) and deletes the hooks' own conversations (plan 7F's delete-typed,
     * which covers every input that a hook gives, tagged or not), so that no test conversation stays among the owner's past
     * conversations and their search. Kestrel deletes only a conversation that a hook's input opened and that no act of
     * the owner's with his words or his choice (his input, or his tap on a card) has reached, never one of the owner's, and keeps its marks across a restart.
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
     * opened, never the owner's conversation or work. A series whose runs gave Kestrel no command, as when every run was
     * untestable, opened nothing, so the cleanup sends nothing. Otherwise it first stops the hooks' own work that still runs
     * ([stopWork]), a goal or Kestrel's own request, and fails when that work does not end. Then delete-typed deletes the
     * hooks' own conversations that are closed and counts the one that is open (`open=`). Only while one is, the cleanup
     * closes the open conversation as long as it carries the hooks' mark, checked on every round of its wait and by Kestrel
     * itself when it ends the quiet time ([closeOwn]), waiting up to [CLEANUP] for a long final reply, and deletes it then.
     * An open conversation without the mark is the owner's and stays open. A conversation of the hooks' that stays open, or
     * a deletion that failed, fails the cleanup, and the console says what to do by hand.
     */
    fun deleteLeft() {
        if (given.isEmpty()) return
        check(stopWork() != WorkStop.OPEN) { "Kestrel's goal or own request of the hooks' could not be stopped; say 'Kestrel, stop', or turn Kestrel off." }
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
     * Ends the hooks' own work that still runs, as the owner's "stop" ends it (design 6.4): the goal of the hooks' own
     * conversation, held or not, or Kestrel's own request there, or the hooks' own request while no conversation is open
     * (remember, forget, "save that as", delete history or a switch with its question, which the stop withdraws). It waits
     * up to [STOP] by the clock for the work's end. Every round reads the work's mark (`typed=`), and the stop goes through
     * goal-control do=stop-typed, which Kestrel checks again on the conversation's loop, so the work of a conversation that
     * the owner opened, or continued or answered with his words, is never stopped: the wait then ends with
     * [WorkStop.OWNERS]. A stop is given once; one that Kestrel refused because no work of the hooks' ran at that moment is
     * sent again on a later round while some does, and one whose answer was lost counts as given, because a second one
     * could reach work that the first is ending. A goal and Kestrel's own request never run together, so one stop ends the
     * work.
     */
    fun stopWork(): WorkStop {
        var given = false
        var end = WorkStop.OPEN
        clock.waitUntil(STOP, POLL) { deadline ->
            val hooks = hooks.until(deadline)
            val state = quietly { hooks.conversationState() } ?: return@waitUntil false
            end = when {
                work(state) == null -> WorkStop.ENDED
                state["typed"] != "yes" -> WorkStop.OWNERS
                else -> {
                    if (!given) given = stopTyped(hooks)
                    WorkStop.OPEN
                }
            }
            end != WorkStop.OPEN
        }
        return end
    }

    // Whether a stop of the hooks' work was given: Kestrel answered that it took it, or the answer was lost. One that Kestrel
    // did not get or refused is sent again on the next round.
    private fun stopTyped(hooks: Hooks): Boolean = try {
        hooks.stopTyped()["stop"] == "sent"
    } catch (e: HookLost) {
        true
    } catch (e: HookSilent) {
        false
    } catch (e: HookError) {
        false
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

    // The work that runs in Kestrel's conversation, as the console names it, or null for none: a goal, held or not, or
    // Kestrel's own request.
    private fun work(state: HookAnswer): String? = when {
        state["goal"].let { it != null && it != "none" } -> "a goal open (${state["goal"]})"
        requestRuns(state) -> "its own request running"
        else -> null
    }

    private fun requestRuns(state: HookAnswer): Boolean = state["request"] == "running"

    companion object {
        val CLOSE = 60.seconds
        val CLEANUP = 5.minutes
        val STOP = 15.seconds

        /** How long after the end of the owner's quiet time, as Kestrel reports it, the wait for his conversation goes on. */
        val OWNER_MARGIN = 30.seconds
        private val POLL = 1.seconds
    }
}

/**
 * The owner's conversation with Kestrel stayed open past its quiet time before a run or between its turns: he is busy with
 * Kestrel, whose conversation the evaluation never ends, so the series stops. The runs that it did not run are written as
 * not run (`result=skipped reason=owner-busy`), which a resumed suite runs again, as it does after Ctrl+C.
 */
class OwnerBusy(message: String) : Exception(message)

/** How [ConversationGuard.stopWork] ended. */
enum class WorkStop {
    /** No work runs: none did, or the hooks' own goal or request ended. */
    ENDED,

    /** The work that runs has no hooks' mark: it is the owner's, which the evaluation never stops. */
    OWNERS,

    /** The hooks' own work did not end within [ConversationGuard.STOP], or Kestrel did not answer. */
    OPEN,
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
