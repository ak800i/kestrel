package dev.kestrel.app.agent

import dev.kestrel.agent.AgentPorts
import dev.kestrel.agent.AgentSettings
import dev.kestrel.agent.goal.GoalPort
import dev.kestrel.agent.goal.HistoryPort
import dev.kestrel.agent.port.AnswerKind
import dev.kestrel.agent.port.ModelPort
import dev.kestrel.agent.port.OwnerPort
import dev.kestrel.safety.Category
import dev.kestrel.safety.HandOverKind
import kotlinx.coroutines.flow.Flow

/** How a task's models are chosen (design D28 of sub-project 5). */
internal sealed interface ModelChoice {
    /**
     * The default profile's two models; [lightweight] replaces the lightweight model's ID for this task only, when the
     * debug hook's extra `model` gave one (design D30's comparison).
     */
    data class Profile(val lightweight: String? = null) : ModelChoice

    /** A model that a debug hook or a device test installed, such as the scripted model of `agent-script`. */
    data class Override(val port: ModelPort) : ModelChoice
}

/**
 * What the agent needs to start task number [task] (design D1 and D2 of sub-project 5): its ID for the logs, the owner's
 * words, the model choice (null for Kestrel's own memory requests that call no model), the goal around the run and the
 * search over past conversations ([goal] and [history], which the caller chooses; today `NoGoal` and `NoHistory`, plan
 * 7A), the debug-only self-chat aliases, [acted], which the screen port calls after each action for the call backstop
 * (design D13), and [local] for a memory request, which needs neither the screen nor the touch watcher. toString prints no
 * words.
 */
internal class TaskStart(
    val task: Int,
    val id: String,
    val text: String,
    val model: ModelChoice?,
    val goal: GoalPort,
    val history: HistoryPort,
    val selfChatAliases: Map<String, String> = emptyMap(),
    val acted: () -> Unit = {},
    val local: Boolean = false,
    /** What the debug build observes of the task; [TaskWitness.NONE] in a release build (plan 6B-basefix). */
    val witness: TaskWitness = TaskWitness.NONE,
) {
    override fun toString(): String = "TaskStart(task=$task, text=${text.length} characters, model=${model?.javaClass?.simpleName}, local=$local)"
}

/**
 * A touch of the owner's that the watcher reported; [lateEchoMillis] is set when it came soon after an action of Kestrel's
 * (D19), and [signal] when no input event carried it: branch B's signal of the screen's events, or a gesture of Kestrel's
 * that a touch cancelled.
 */
internal data class OwnerTouch(val lateEchoMillis: Long? = null, val signal: Boolean = false)

/**
 * The owner port of one task together with the Orchestrator's effects that steer it (design D2 of sub-project 5).
 * `AppOwnerPort` implements it; `AgentRunner` calls these functions on the controller's thread, and they must not block.
 */
internal interface TaskOwner : OwnerPort {
    /** [dev.kestrel.app.orchestrator.Effect.AnswerTask]: the answer that the Rules read, or null when the wait ended. */
    fun answered(answer: String?)

    /** "Kestrel, continue" ended the hand-over. */
    fun continued()

    /** A call or the lock paused the task: an open question ends as interrupted, and the gate closes. */
    fun paused()

    /** The owner said yes to "Continue the task?": the gate opens. */
    fun resumed()

    /** The hand-over lasted 3 minutes. */
    fun handOverExpired()

    /** The kind of the hand-over that is open now, or null; the debug hook `agent-state` reads it. */
    val handOverKind: HandOverKind?

    /** The category of the confirmation that is open now, or null; the debug hook `agent-state` reads it (design D28). */
    val confirming: Category? get() = null

    /**
     * The kind of the question that is open now, or null; the debug hook `agent-state` reads it, so that :tools:eval
     * answers look_closer's screenshot question apart from the model's own question (plan 6B-basefix).
     */
    val asking: AnswerKind? get() = null

    /** The task has ended: every wait ends, and the card and the bar's kept text go. */
    fun close()
}

/** What [AgentEnvironment.prepare] gives (design D32 item 2 of sub-project 5). */
internal sealed interface Prepared {
    /**
     * Everything that the task's agent needs: its [ports] and [settings], its [owner], the contact names that the
     * allowance may read ([knownNames], D13), the owner's [touches] while the watcher runs, the provider's name for the
     * spoken errors, and [release], which stops the touch watcher and frees what the task held.
     */
    class Ready(
        val ports: AgentPorts,
        val settings: AgentSettings,
        val owner: TaskOwner,
        val knownNames: Set<String>,
        val touches: Flow<OwnerTouch>,
        val providerName: String,
        val release: suspend () -> Unit,
    ) : Prepared {
        override fun toString(): String = "Ready(knownNames=${knownNames.size})"
    }

    /** The task cannot start; [sentence] is what Kestrel says. */
    data class Refused(val sentence: String) : Prepared {
        override fun toString(): String = "Refused(${sentence.length} characters)"
    }
}

/** Builds what each task needs from Kestrel's parts (design D1 of sub-project 5); `AppAgentEnvironment` is `:app`'s. */
internal fun interface AgentEnvironment {
    suspend fun prepare(start: TaskStart): Prepared
}
