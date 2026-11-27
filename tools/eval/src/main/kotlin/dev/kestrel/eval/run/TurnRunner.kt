package dev.kestrel.eval.run

import dev.kestrel.eval.device.DeviceCommandFailed
import dev.kestrel.eval.device.waitUntil
import dev.kestrel.eval.hook.HookAnswer
import dev.kestrel.eval.hook.HookError
import dev.kestrel.eval.hook.HookLost
import dev.kestrel.eval.hook.HookSilent
import dev.kestrel.eval.task.Answers
import dev.kestrel.eval.task.RunScope
import dev.kestrel.eval.task.Task
import dev.kestrel.eval.task.TaskKind
import dev.kestrel.eval.task.Turn
import kotlin.time.Duration

/** How a run's turns ended: all of them ran, a command did not reach Kestrel, or a command cut the rest short. */
internal enum class TurnsEnd { DONE, UNDELIVERED, CUT }

/**
 * The run's way through a task's turns (design 14.4 of sub-project 7), with task-check.ps1's limits for each command
 * (design D5 of sub-project 6): each [Turn.Say] gives Kestrel a new command with a tag of its own, and the run polls
 * agent-task every 2 s for up to 10 minutes, answers the command's questions as the task says with 3 s of quiet after an
 * answer, and stops waiting after 30 s without an answer of the hooks. A command that timed out or stalled, or that
 * Kestrel refused before it ran, ends the turns, because nothing after it would test what the task names.
 */
internal class TurnRunner(
    private val task: Task,
    private val number: Int,
    private val scope: RunScope,
    private val state: RunState,
    private val options: RunOptions,
    private val tags: () -> String,
    private val stopIfStopping: () -> Unit,
) {
    private val hooks = scope.hooks
    private val clock = scope.clock

    fun run(): TurnsEnd {
        for (turn in task.turns) {
            stopIfStopping()
            val end = when (turn) {
                is Turn.Say -> say(turn)
            }
            if (end != null) return end
        }
        return TurnsEnd.DONE
    }

    private fun say(turn: Turn.Say): TurnsEnd? {
        val words = "Kestrel, " + turn.words.fill(scope.values)
        val command = give(turn.ends) { tag -> hooks.voiceHeard(words, tag, options.live, options.model, scope.values.marker) }
            ?: return TurnsEnd.UNDELIVERED
        return follow(command)
    }

    /**
     * Gives Kestrel a new command through [send] with a new tag. A lost answer is read back: the command exists, or it did
     * not reach Kestrel, and then it is null.
     */
    private fun give(expected: String?, send: (String) -> Unit): SentCommand? {
        val command = SentCommand(tags(), expected)
        state.commands.add(command)
        scope.tag = command.tag
        try {
            command.sent = true
            send(command.tag)
        } catch (e: HookLost) {
            if (!exists(command.tag)) {
                state.commands.remove(command)
                scope.tag = state.tag
                scope.console.warn("Task ${task.name}, run $number: the task did not reach Kestrel.")
                return null
            }
        }
        return command
    }

    /** Follows [command] to its end and answers its questions; null when the turns go on. */
    private fun follow(command: SentCommand): TurnsEnd? {
        val start = clock.elapsed()
        fun elapsed(): Duration = clock.elapsed() - start
        var heard = Duration.ZERO
        var quietUntil = Duration.ZERO
        while (elapsed() < Runner.LIMIT) {
            clock.sleep(Runner.POLL)
            val pairs = quietly { hooks.agentTask(command.tag) }
            if (pairs == null) {
                if (elapsed() - heard > Runner.STALL) {
                    state.stalled = true
                    return TurnsEnd.CUT
                }
                continue
            }
            heard = elapsed()
            if (pairs["state"] == "done") return ended(command, pairs)
            if (elapsed() < quietUntil) continue
            val asked = quietly { hooks.agentState(command.tag) } ?: continue
            val kind = Answers.kind(asked)
            state.record.saw(kind)
            if (kind == null) continue
            if (answer(command, asked, kind)) quietUntil = elapsed() + Runner.QUIET
        }
        state.timedOut = true
        return TurnsEnd.CUT
    }

    // The command's end; one that Kestrel refused before it ran ends the turns, because nothing was tested.
    private fun ended(command: SentCommand, pairs: HookAnswer): TurnsEnd? {
        command.answer = pairs
        state.record.ended()
        if (pairs["end"] in Refusals.ENDS) {
            state.refusal = Refusals.kind(hooks, command.tag)
            return TurnsEnd.CUT
        }
        return null
    }

    /**
     * Answers the open question of [kind] as the task says; true when an answer went to Kestrel, after which the run keeps
     * quiet for a moment. A lost or refused answer is not sent again: the next poll answers only if the same question is
     * still open.
     */
    private fun answer(command: SentCommand, asked: HookAnswer, kind: String): Boolean {
        val reply = task.answers.reply(asked)
        if (reply == null) {
            // "Which number should I use?" stays unanswered (the owner's decision P1).
            if (kind == Answers.PICK_NUMBER) state.record.keepOpen()
            return false
        }
        val confirming = asked["confirming"].takeIf { asked["asking"] == "confirm" }
        // A confirmation that the task does not name was cancelled; outside the injection fixtures, whose defence it is, the run fails.
        if (confirming != null && reply == "cancel" && task.kind != TaskKind.INJECTION) state.refused = confirming
        if (confirming != null && reply == "no") state.declined.add(confirming)
        when (deliver { hooks.agentAnswer(command.tag, reply, confirming) }) {
            Delivery.DELIVERED -> state.record.answered(reply)
            Delivery.LOST -> state.record.lost(reply)
            Delivery.REFUSED -> Unit
        }
        return true
    }

    /**
     * Sends an answer that acts once: [Delivery.REFUSED] when Kestrel answered with an error, such as "the task asks
     * nothing"; [Delivery.LOST] when its reply did not come back, after which Kestrel may have received it or not.
     */
    private fun deliver(answer: () -> HookAnswer): Delivery = try {
        answer()
        Delivery.DELIVERED
    } catch (e: HookError) {
        Delivery.REFUSED
    } catch (e: HookSilent) {
        Delivery.LOST
    } catch (e: HookLost) {
        Delivery.LOST
    } catch (e: DeviceCommandFailed) {
        Delivery.LOST
    }

    private enum class Delivery { DELIVERED, REFUSED, LOST }

    /** Whether Kestrel has a command with [tag], read once a second for up to 10 s by the clock after the command's answer was lost. */
    private fun exists(tag: String): Boolean =
        clock.waitUntil(Runner.EXISTS, Runner.REREAD) { deadline -> quietly { hooks.until(deadline).agentTask(tag) } != null }
}
