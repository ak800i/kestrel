package dev.kestrel.conversation.goal

import dev.kestrel.agent.EndReason
import dev.kestrel.agent.Interruption
import dev.kestrel.agent.TaskCancellation
import dev.kestrel.agent.goal.Checklist
import dev.kestrel.agent.goal.GoalPort
import dev.kestrel.agent.goal.Steer
import dev.kestrel.agent.goal.TraceEntry
import dev.kestrel.conversation.context.SteeringMessage
import dev.kestrel.conversation.model.RoundEnd
import dev.kestrel.safety.Category
import kotlinx.coroutines.Job

/**
 * What a goal tells the conversation as its rounds run: its steps, the record's traces, the allowance, steering, touches
 * and its ends.
 */
interface GoalListener {
    /** Step [step] of the running round starts (1, 2, …); for the bar's step (design 10.5). Must not block. */
    fun stepStarted(step: Int)

    /** Something that the goal's record needs; returns once the record holds it and is saved. */
    suspend fun traced(entry: TraceEntry)

    suspend fun checklist(checklist: Checklist)

    /** The goal's request asked for [categories] (D16). */
    fun allowed(categories: Set<Category>)

    /** The steering that waits changed. */
    fun steeringChanged()

    /** The touch watcher saw the owner's touch. */
    fun touched()

    fun ended(end: RoundEnd)
}

/**
 * One goal of the conversation as it runs in this process (design 7 of sub-project 7), across its rounds: the agent's
 * `GoalPort`, whose steering is its [inbox] and whose steps and traces go to its [listener]; its [record]'s number; the
 * [command] number of the input that started or last resumed it; the debug hook's [options] of the latest input that did;
 * and the job of its round, which a touch interrupts (D13) and "stop" cancels.
 */
class RunningGoal(
    val record: Int,
    command: Int,
    options: Map<String, String>,
    steering: List<SteeringMessage>,
    val listener: GoalListener,
) : GoalPort {
    @Volatile
    var command: Int = command

    @Volatile
    var options: Map<String, String> = options

    val inbox = SteeringInbox(steering) { listener.steeringChanged() }

    /** The contact names that the newest round's allowance read, for the allowance of the owner's steering. */
    @Volatile
    var knownNames: Set<String> = emptySet()
        internal set

    @Volatile
    internal var job: Job? = null

    // Kestrel's own sentence that replaces the report's reason, as the call backstop's, for the round that the cancel
    // ended; each new round starts without one.
    @Volatile
    internal var stopSentence: String? = null

    /** The owner's [message] for the goal's next turn; false once the goal has given its final words (its inbox is closed). */
    fun steer(message: SteeringMessage): Boolean = inbox.add(message)

    /** Stops the round at once; the goal stays open and held (D13). */
    fun interrupt(cause: Interruption) {
        job?.cancel(TaskCancellation(EndReason.INTERRUPTED, cause))
    }

    /** Ends the round with [reason]; [sentence] replaces the report's reason. */
    fun cancel(reason: EndReason, sentence: String? = null) {
        stopSentence = sentence
        job?.cancel(TaskCancellation(reason))
    }

    override fun takeSteering(): List<Steer> = inbox.drain().map { it.steer }

    override fun stepStarted(step: Int) = listener.stepStarted(step)

    override suspend fun checklistChanged(checklist: Checklist) = listener.checklist(checklist)

    override suspend fun traced(entry: TraceEntry) = listener.traced(entry)

    override fun toString(): String = "RunningGoal(record=$record, command=$command, inbox=$inbox)"
}
