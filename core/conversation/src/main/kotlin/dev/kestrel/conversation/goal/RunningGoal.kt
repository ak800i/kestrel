package dev.kestrel.conversation.goal

import dev.kestrel.agent.EndReason
import dev.kestrel.agent.Interruption
import dev.kestrel.agent.TaskCancellation
import dev.kestrel.agent.TaskReport
import dev.kestrel.agent.goal.Checklist
import dev.kestrel.agent.goal.GoalPort
import dev.kestrel.agent.goal.Steer
import dev.kestrel.agent.goal.SteerChannel
import dev.kestrel.agent.goal.TraceEntry
import dev.kestrel.conversation.model.InputChannel
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
 * The end of a round of the goal of [command]: its [report]; [answer], Kestrel's final words for it, which the record and
 * the outcome keep; and [reply], what of them is still to say: null when nothing is, as after a routine offer, which said
 * the answer before it asked.
 */
data class RoundEnd(val command: Int, val report: TaskReport, val answer: String?, val reply: String?) {
    override fun toString(): String = "RoundEnd(command=$command, report=$report, answer=${answer?.length}, reply=${reply?.length})"
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
    steering: List<Steer>,
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

    /** The owner's [text] for the goal's next turn; false once the goal has given its final words (its inbox is closed). */
    fun steer(text: String, channel: InputChannel): Boolean = inbox.add(Steer(text, channel.toSteer()))

    /** Stops the round at once; the goal stays open and held (D13). */
    fun interrupt(cause: Interruption) {
        job?.cancel(TaskCancellation(EndReason.INTERRUPTED, cause))
    }

    /** Ends the round with [reason]; [sentence] replaces the report's reason. */
    fun cancel(reason: EndReason, sentence: String? = null) {
        stopSentence = sentence
        job?.cancel(TaskCancellation(reason))
    }

    override fun takeSteering(): List<Steer> = inbox.drain()

    override fun stepStarted(step: Int) = listener.stepStarted(step)

    override suspend fun checklistChanged(checklist: Checklist) = listener.checklist(checklist)

    override suspend fun traced(entry: TraceEntry) = listener.traced(entry)

    override fun toString(): String = "RunningGoal(record=$record, command=$command, inbox=$inbox)"
}

/** The agent's name for the channel of a steering message. */
fun InputChannel.toSteer(): SteerChannel = if (this == InputChannel.VOICE) SteerChannel.SPOKEN else SteerChannel.TYPED

fun SteerChannel.toInput(): InputChannel = if (this == SteerChannel.SPOKEN) InputChannel.VOICE else InputChannel.TEXT
