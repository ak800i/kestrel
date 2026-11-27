package dev.kestrel.eval.task

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One turn of a task's conversation with Kestrel (design 14.4 and 15.3 of sub-project 7). A task is a list of turns; a
 * task of one [Say] is a task of sub-project 6: one spoken command after "Kestrel, ", which the run follows to its end.
 */
sealed interface Turn {
    /**
     * The owner's [words], which give Kestrel a new command on [channel]; the run then follows it [until] its end, or until
     * its goal acts, so that a later turn can act during it. The end must be [ends], an `EndReason` name, or any end with
     * null, unless the command is the run's last, whose end the task's [VerdictRule] judges; for a command that the run
     * follows until its goal acts, the [AwaitEnd] that follows it names the end.
     */
    data class Say(
        val words: Request,
        val channel: TurnChannel = TurnChannel.SPOKEN,
        val until: Until = Until.END,
        val ends: String? = FINISHED,
    ) : Turn

    /**
     * The owner's [words] while the current command's goal acts, which steer it (design 7.3): spoken after "Kestrel, ",
     * because steering by voice needs the name (D9), or typed. They give Kestrel no command of their own.
     */
    data class Steer(val words: Request, val channel: TurnChannel = TurnChannel.SPOKEN) : Turn

    /** The run follows the current command to its end, which must be [ends], as for [Say]. */
    data class AwaitEnd(val ends: String? = FINISHED) : Turn

    /**
     * Kestrel's process ends (the hook crash, spec 16.1), and the run waits for the new process, its accessibility service
     * and listening. The current command ends with the process, its end unread. The run then applies again every setup step
     * of its own that held state in the process ([ProcessSetup]), the scripted model of a run without a model with its dry
     * run's next script ([DryRun.restarts]) among them.
     */
    data object Restart : Turn

    /** A step of the run between two turns, such as the end of the quiet time or the owner's touch. */
    data class Act(val action: TurnAction) : Turn

    /** [check] must hold within [within]; the run reads it every 2 s, and when it does not hold, the run fails (`reason=check`). */
    data class Expect(val check: Check, val within: Duration = 30.seconds) : Turn

    companion object {
        const val FINISHED = "FINISHED"
    }
}

/**
 * How the owner gives a command or steers (design 6.6, 10.1 and 11 of sub-project 7): spoken after "Kestrel, ", spoken
 * without the name inside the follow-up window that a spoken final reply opens, or typed as on Kestrel's conversation
 * screen. The run answers a command's questions on the channel of the owner's latest input: by voice, or on the text
 * channel's card after a typed input.
 */
enum class TurnChannel { SPOKEN, FOLLOW_UP, TYPED }

/** How far the run follows a command before its next turn: to its end, or until its goal acts. */
enum class Until { END, ACTING }

/**
 * A step of the run between two turns (design 15.3 of sub-project 7), such as the end of the quiet time, the owner's touch
 * or a test SMS deleted. Its [toString] names it on the console, with fixed words only.
 */
interface TurnAction {
    fun run(scope: RunScope)
}

/** The turns of a task (design 14.4 of sub-project 7). */
object Turns {
    /** A task of one spoken command, the form of every task of sub-project 6. */
    fun one(request: String): List<Turn> = listOf(Turn.Say(Request(request)))

    /**
     * Why [turns] cannot be a task's, or null when they can: the first turn is a command, which no follow-up window can
     * hold; a command needs the one before it ended; steering and [Turn.AwaitEnd] need a command whose goal acts; steering
     * is spoken with the name or typed; a follow-up needs a spoken command before it; and the turns end with no command
     * running.
     */
    fun problem(turns: List<Turn>): String? {
        val first = turns.firstOrNull() as? Turn.Say ?: return "its first turn must be a command"
        if (first.channel == TurnChannel.FOLLOW_UP) return "its first command cannot speak in a follow-up window"
        var running = false
        var spoken = false
        for ((index, turn) in turns.withIndex()) {
            val at = "turn ${index + 1}"
            when (turn) {
                is Turn.Say -> {
                    if (running) return "$at gives a command while the one before it runs; await its end first"
                    if (turn.channel == TurnChannel.FOLLOW_UP && !spoken) return "$at speaks in a follow-up window that no spoken command opened"
                    running = turn.until == Until.ACTING
                    spoken = turn.channel != TurnChannel.TYPED
                }
                is Turn.Steer -> {
                    if (!running) return "$at steers no goal that acts"
                    if (turn.channel == TurnChannel.FOLLOW_UP) return "$at steers inside a follow-up window, which steering never uses"
                    spoken = turn.channel != TurnChannel.TYPED
                }
                is Turn.AwaitEnd -> {
                    if (!running) return "$at awaits no command"
                    running = false
                }
                Turn.Restart -> {
                    running = false
                    spoken = false
                }
                is Turn.Act, is Turn.Expect -> Unit
            }
        }
        return if (running) "its turns end while a command runs; await its end" else null
    }
}
