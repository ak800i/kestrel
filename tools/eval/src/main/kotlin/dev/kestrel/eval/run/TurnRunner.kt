package dev.kestrel.eval.run

import dev.kestrel.eval.device.Deadline
import dev.kestrel.eval.device.DeviceChanged
import dev.kestrel.eval.device.DeviceCommandFailed
import dev.kestrel.eval.device.waitUntil
import dev.kestrel.eval.hook.HookAnswer
import dev.kestrel.eval.hook.HookError
import dev.kestrel.eval.hook.HookLost
import dev.kestrel.eval.hook.HookSilent
import dev.kestrel.eval.hook.Hooks
import dev.kestrel.eval.task.Answers
import dev.kestrel.eval.task.ProcessSetup
import dev.kestrel.eval.task.Reply
import dev.kestrel.eval.task.RunScope
import dev.kestrel.eval.task.Task
import dev.kestrel.eval.task.TaskKind
import dev.kestrel.eval.task.Turn
import dev.kestrel.eval.task.TurnChannel
import dev.kestrel.eval.task.Until
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** How a run's turns ended: all of them ran, a command did not reach Kestrel, or a turn cut the rest short. */
internal enum class TurnsEnd { DONE, UNDELIVERED, CUT }

/**
 * The run's way through a task's turns (design 14.4 and 15.3 of sub-project 7), with task-check.ps1's limits for each
 * command (design D5 of sub-project 6). A [Turn.Say] gives Kestrel a new command with a tag of its own: spoken after
 * "Kestrel, ", spoken without the name once Kestrel's follow-up window is open, or typed as on its conversation screen. The
 * run follows the command to its end, or until its goal acts: it polls agent-task every 2 s for up to 10 minutes, answers
 * the command's questions as the task says on the channel of the owner's latest input, with 3 s of quiet after an answer,
 * and stops after 30 s without an answer of the hooks. A [Turn.Steer] gives the goal that acts the owner's words, a
 * [Turn.AwaitEnd] follows the current command to its end, a [Turn.Restart] ends Kestrel's process, waits for the new one
 * and applies the run's [renewed] steps again, a [Turn.Act] runs a step, and a [Turn.Expect] reads a check until it holds.
 * A command that timed out or stalled, one that Kestrel refused before it ran, a check that did not hold, a step that
 * failed, and a command followed until its goal acts that ended before the run saw its goal act end the turns, because
 * nothing after them would test what the task names: a steer, an await or a restart then has no goal that acts.
 */
internal class TurnRunner(
    private val task: Task,
    private val number: Int,
    private val scope: RunScope,
    private val state: RunState,
    private val options: RunOptions,
    private val tags: () -> String,
    private val stopIfStopping: () -> Unit,
    private val conversations: ConversationGuard,
    private val restarter: KestrelRestart,
    private val renewed: List<ProcessSetup>,
) {
    private val hooks = scope.hooks
    private val clock = scope.clock
    private val console = scope.console

    fun run(): TurnsEnd {
        for ((index, turn) in task.turns.withIndex()) {
            stopIfStopping()
            val end = when (turn) {
                is Turn.Say -> say(turn)
                is Turn.Steer -> steer(index, turn)
                is Turn.AwaitEnd -> awaitEnd(index, turn)
                Turn.Restart -> step(index, "the restart of Kestrel") { restart() }
                is Turn.Act -> step(index, turn.action.toString()) { turn.action.run(scope) }
                is Turn.Expect -> expect(index, turn)
            }
            if (end != null) return end
        }
        return TurnsEnd.DONE
    }

    private fun say(turn: Turn.Say): TurnsEnd? {
        val words = turn.words.fill(scope.values)
        if (turn.channel == TurnChannel.FOLLOW_UP && !followUpOpen()) {
            return unmet("the follow-up window", "Kestrel's follow-up window did not open within ${FOLLOW_UP.inWholeSeconds} s.")
        }
        state.channel = turn.channel
        val command = give(turn.ends) { tag ->
            when (turn.channel) {
                TurnChannel.SPOKEN -> hooks.voiceHeard("Kestrel, $words", tag, options.live, options.model, scope.values.marker)
                TurnChannel.FOLLOW_UP -> hooks.voiceHeard(words, tag, options.live, options.model, scope.values.marker)
                TurnChannel.TYPED -> hooks.conversationType(words, tag, options.live, options.model, scope.values.marker)
            }
        } ?: return TurnsEnd.UNDELIVERED
        return follow(command, acting = turn.until == Until.ACTING)
    }

    // Design 7.3 of sub-project 7: the owner's words reach the goal that acts at its next step. They give Kestrel no
    // command, so the run reads no end of theirs and follows the steered command on. Without a command of the run's that
    // runs, the words would start a new command, which the run would never follow, so the turn fails the run instead.
    private fun steer(index: Int, turn: Turn.Steer): TurnsEnd? {
        state.current ?: return unmet("a goal to steer", "turn ${index + 1} steers, but no command of the run's runs.")
        val words = turn.words.fill(scope.values)
        state.channel = turn.channel
        try {
            if (turn.channel == TurnChannel.TYPED) hooks.conversationType(words, tags()) else hooks.voiceHeard("Kestrel, $words", tags())
        } catch (e: HookLost) {
            // Steering has no command to read back; the steered goal's end and the task's checks show whether it arrived.
        }
        return null
    }

    // Follows the current command to its end, now expected as [Turn.AwaitEnd.ends]; without one, nothing would be awaited.
    private fun awaitEnd(index: Int, turn: Turn.AwaitEnd): TurnsEnd? {
        val command = state.current ?: return unmet("a command to await", "turn ${index + 1} awaits a command's end, but no command of the run's runs.")
        command.expected = turn.ends
        return follow(command, acting = false)
    }

    /**
     * Fails the run with `reason=check` ([RunState.unmet]): the turns stop at [what], which a turn needed and did not get,
     * and the console says [why].
     */
    private fun unmet(what: String, why: String): TurnsEnd {
        state.unmet = what
        console.warn("Task ${task.name}, run $number: $why")
        return TurnsEnd.CUT
    }

    /** Waits up to [FOLLOW_UP] by the clock for Kestrel's follow-up window (design 6.6), which opens once its spoken final reply has ended. */
    private fun followUpOpen(): Boolean =
        clock.waitUntil(FOLLOW_UP, WINDOW_POLL) { deadline -> quietly { hooks.until(deadline).voiceState() }?.get("followUp") == "open" }

    /**
     * Gives Kestrel a new command through [send] with a new tag. A lost answer is read back: the command exists, or it did
     * not reach Kestrel, and then it is null.
     */
    private fun give(expected: String?, send: (String) -> Unit): SentCommand? {
        val command = SentCommand(tags(), expected)
        state.commands.add(command)
        scope.tag = command.tag
        // Before the words go: a command whose answer is lost may still have opened a conversation of the series' own.
        conversations.gave(command.tag)
        try {
            command.sent = true
            send(command.tag)
        } catch (e: HookLost) {
            if (!exists(command.tag)) {
                state.commands.remove(command)
                scope.tag = state.tag
                console.warn("Task ${task.name}, run $number: the task did not reach Kestrel.")
                return null
            }
        }
        return command
    }

    /**
     * Follows [command] to its end, or with [acting] until the run sees its goal act with no question open, and answers its
     * questions; null when the turns go on. A command followed with [acting] that ends before the run saw its goal act fails
     * the run ([unmet]), because the turns after it need a goal that acts.
     */
    private fun follow(command: SentCommand, acting: Boolean): TurnsEnd? {
        val start = clock.elapsed()
        fun elapsed(): Duration = clock.elapsed() - start
        // Every read and answer ends by the command's limit and by the stall rule's end, whichever comes first, so that no
        // read outlives either ([Deadline]); the stall rule's end moves on with each answer of the hooks.
        val limit = Deadline(clock, Runner.LIMIT)
        var stall = Deadline(clock, Runner.STALL)
        var quietUntil = Duration.ZERO
        while (!limit.passed) {
            clock.sleep(minOf(Runner.POLL, limit.left))
            val reads = hooks.until(limit.earlier(stall))
            val pairs = quietly { reads.agentTask(command.tag) }
            if (pairs == null) {
                if (stall.passed) {
                    state.stalled = true
                    return TurnsEnd.CUT
                }
                continue
            }
            stall = Deadline(clock, Runner.STALL)
            if (pairs["state"] == "done") {
                return ended(command, pairs, reads) ?: if (acting) {
                    unmet("the goal acting", "the command ended before the run saw its goal act, so the turns after it would steer, await or restart no goal.")
                } else {
                    null
                }
            }
            if (elapsed() < quietUntil) continue
            val asked = quietly { reads.agentState(command.tag) } ?: continue
            val kind = Answers.kind(asked)
            command.record.saw(kind)
            if (kind == null) {
                // A later turn steers the goal, touches the screen or ends Kestrel's process while the goal acts.
                if (acting && asked["phase"] == "running") return null
                continue
            }
            if (answer(command, asked, kind, reads)) quietUntil = elapsed() + Runner.QUIET
        }
        state.timedOut = true
        return TurnsEnd.CUT
    }

    // The command's end; one that Kestrel refused before it ran ends the turns, because nothing was tested. The refusal's
    // kind is read through the wait's [reads].
    private fun ended(command: SentCommand, pairs: HookAnswer, reads: Hooks): TurnsEnd? {
        command.answer = pairs
        command.record.ended()
        if (pairs["end"] in Refusals.ENDS) {
            state.refusal = Refusals.kind(reads, command.tag)
            return TurnsEnd.CUT
        }
        return null
    }

    /**
     * Answers the open question of agent-state's [asked], of [kind], as the task says, after the run's questions before it
     * ([RunState.before]), on the card over the app or the text channel's card after a typed input; true when an answer
     * went to Kestrel, after which the run keeps quiet for a moment. The answer goes through the wait's [hooks]. A lost or
     * refused answer is not sent again: the next poll answers only if the same question is still open.
     */
    private fun answer(command: SentCommand, asked: HookAnswer, kind: String, hooks: Hooks): Boolean {
        val reply = when (val chosen = task.answers.reply(asked, state.before(command))) {
            null -> return false
            // "Which number should I use?" (the owner's decision P1) and a limit's "Continue?" that the task leaves to
            // Kestrel's own wait (design 7.4 of sub-project 7) stay unanswered.
            Reply.LeaveOpen -> {
                command.record.keepOpen()
                return false
            }
            is Reply.Say -> chosen.words
        }
        val confirming = asked["confirming"].takeIf { asked["asking"] == "confirm" }
        // A confirmation that the task does not name was cancelled; outside the injection fixtures, whose defence it is, the run fails.
        if (confirming != null && reply == "cancel" && task.kind != TaskKind.INJECTION) state.refused = confirming
        if (confirming != null && reply == "no") state.declined.add(confirming)
        val channel = channelFor(kind)
        when (deliver { hooks.agentAnswer(command.tag, reply, confirming, channel) }) {
            Delivery.DELIVERED -> command.record.answered(reply)
            Delivery.LOST -> command.record.lost(reply)
            Delivery.REFUSED -> Unit
        }
        return true
    }

    // Kestrel's process ends with the current command, which is lost with its tag; every step of the run that held state in
    // the process applies its change again, the scripted model with the dry run's next script among them.
    private fun restart() {
        state.commands.filter { it.open }.forEach { it.lost = true }
        restarter.restart()
        renewed.forEach { it.renew(scope) }
    }

    // A restart or a step between turns that failed leaves the run unable to go on, as a failed setup does.
    private fun step(index: Int, what: String, block: () -> Unit): TurnsEnd? = try {
        block()
        null
    } catch (e: Exception) {
        if (e is DeviceChanged || e is Stopping) throw e
        state.stepFailed = true
        console.warn("Task ${task.name}, run $number: turn ${index + 1}, $what, failed (${e.message ?: e.javaClass.simpleName}); the run fails, and the next run goes on.")
        TurnsEnd.CUT
    }

    // Design 15.3 of sub-project 7: a check between turns, read every 2 s with the newest command's end until it holds, for
    // up to its time by the clock; the check reads through the run's scope bounded by the wait ([RunScope.until]).
    private fun expect(index: Int, turn: Turn.Expect): TurnsEnd? {
        val held = clock.waitUntil(turn.within, Runner.POLL) { deadline ->
            val answer = state.commands.lastOrNull { it.answer != null }?.answer ?: HookAnswer.EMPTY
            quietly { turn.check.passes(scope.until(deadline), answer) } == true
        }
        if (held) return null
        state.unmet = turn.check.toString()
        console.warn("Task ${task.name}, run $number: turn ${index + 1}, ${turn.check}, did not hold within ${turn.within.inWholeSeconds} s.")
        return TurnsEnd.CUT
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

    // Design 10.3, 12.2 and 12.3 of sub-project 7: after a typed input, the owner answers a confirmation, a hand-over and a
    // pick on the card over the app (plan 7F's Task 13a), and any other question on the text channel's card; after a
    // spoken one, by voice. The run presses confirm, no, cancel or a number on the card over the app, never Continue, so
    // the card desk answers at once or refuses the press (the question stays open, and the next poll answers again); it
    // never holds the press until the phone's unlock (`held … until=unlock`), which only a Continue on the lock screen gets.
    private fun channelFor(kind: String): String? = when {
        state.channel != TurnChannel.TYPED -> null
        Answers.overlayOnly(kind) -> CARD
        else -> TEXT
    }

    companion object {
        /** How long a run waits for Kestrel's follow-up window, which opens once the spoken final reply ended. */
        val FOLLOW_UP = 60.seconds
        private val WINDOW_POLL = 500.milliseconds
        private const val TEXT = "text"
        private const val CARD = "card"
    }
}
