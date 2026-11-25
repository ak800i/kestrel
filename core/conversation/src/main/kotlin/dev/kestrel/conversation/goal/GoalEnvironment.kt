package dev.kestrel.conversation.goal

import dev.kestrel.agent.AgentPorts
import dev.kestrel.agent.AgentSettings
import dev.kestrel.agent.EndReason
import dev.kestrel.agent.TaskReport
import dev.kestrel.agent.goal.GoalPort
import dev.kestrel.agent.goal.HistoryPort
import dev.kestrel.agent.port.OwnerPort
import kotlinx.coroutines.flow.Flow

/**
 * What a goal's round needs from Kestrel's phone (design 5.5 and 7.1 of sub-project 7). `:app`'s `AppGoalEnvironment`
 * (plan 7D) implements it, with `RoundModels` for the model choice: the model or the debug build's scripted model and
 * live session, the screen and the touch watcher, the data tools, the self-chat aliases and the call backstop. The JVM
 * tests use the fixtures' `FakeGoalEnvironment`.
 */
interface GoalEnvironment {
    /** The ports and settings of one round of [start], or why it cannot start. */
    suspend fun prepare(start: RoundStart): RoundSetup

    /** Kestrel's final words for [report], `:app`'s `AgentTexts.sentence` with the profile's provider name. */
    fun sentence(report: TaskReport): String
}

/** A round of a goal, or the model call of "save that as" (D23 of sub-project 5), which needs no screen and no touches. */
enum class RoundPurpose { GOAL, ROUTINE }

/**
 * One round to prepare: the input's [command] number, the debug hook's [options] (`live`, `model`), and the round's own
 * [owner], [goal] and [history], which the environment puts into the agent's ports.
 */
class RoundStart(
    val command: Int,
    val options: Map<String, String>,
    val owner: OwnerPort,
    val goal: GoalPort,
    val history: HistoryPort,
    val purpose: RoundPurpose,
) {
    override fun toString(): String = "RoundStart(command=$command, options=${options.keys.sorted()}, purpose=$purpose)"
}

/** What [GoalEnvironment.prepare] gives. */
sealed interface RoundSetup {
    /**
     * Everything that the round's agent needs: its [ports] and [settings], its [taskId] for the logs, the contact names
     * that the allowance may read ([knownNames]), the owner's [touches] while the watcher runs, whether a finished goal
     * may offer a routine ([offersRoutines]: never in a debuggable build), and [release], which stops the watcher.
     */
    class Ready(
        val ports: AgentPorts,
        val settings: AgentSettings,
        val taskId: String,
        val knownNames: Set<String>,
        val touches: Flow<Unit>,
        val offersRoutines: Boolean,
        val release: suspend () -> Unit,
    ) : RoundSetup {
        override fun toString(): String = "Ready(knownNames=${knownNames.size}, offersRoutines=$offersRoutines)"
    }

    /** The round cannot start: [sentence] is what Kestrel says, and [end] the goal's end, as `NO_PROFILE` or `MODEL_UNUSABLE`. */
    data class Refused(val sentence: String, val end: EndReason) : RoundSetup {
        override fun toString(): String = "Refused(${sentence.length} chars, end=$end)"
    }
}

/**
 * The screen's turn (spec 7.3; design 6.5 and 7.1 of sub-project 7), the one rule for who acts on the screen, in both
 * directions: [take] gives a goal's round the turn once no instant command holds the screen, and the round holds it until
 * [release], so that no instant command starts while it acts. [after] is the number of the owner's input that lets the round act
 * now (`OwnerInput.id`), or null for a round that goes on by itself. A command that the owner started before that input is
 * asked to let go (the Orchestrator cancels it), because his newer input chose the goal; a command that he started after
 * it, or while the round goes on by itself, is his newest act, so the round waits until its job has ended (D15; spec 7.3).
 * [take] is true when it waited, because the screen may have changed meanwhile. Each round takes the turn at its start and
 * as every wait for the owner ends, and lets go as each such wait starts, so that the instant commands work during its
 * hand-overs and pauses, and when it ends, on every path (`GoalRunner`, `TaskRun.waitForOwner`); a pause that the
 * conversation starts lets go of it as soon as no step that a gate let through can act any more, at once while the round
 * waits for its model (`ScreenTakingGoal`; plan 7E).
 * [waiting] hears it each time a [take] starts to wait for an instant command that goes on, the owner's newest act, so
 * that the conversation's view can say why the goal does not move (plan 7E); a command that the round has asked to let go
 * is no such wait. `:app`'s `ScreenTurns` (plan 7D) implements it.
 */
interface ScreenTurn {
    suspend fun take(after: Long?, waiting: () -> Unit): Boolean

    fun release()
}
