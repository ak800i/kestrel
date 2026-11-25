package dev.kestrel.conversation.engine

import dev.kestrel.agent.TaskReport
import dev.kestrel.agent.goal.Checklist
import dev.kestrel.agent.goal.ItemState
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.machine.QuietClock
import dev.kestrel.conversation.model.CommandRecord
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.GoalPhase
import dev.kestrel.conversation.model.InputChannel

/**
 * What the voice rules, the conversation screen, the goal service and the hooks read (design 5.3 of sub-project 7): the
 * open conversation, the newest command's number in this process, the goal's phase and, while it works, its step (counted
 * across its rounds, for the bar's "Step 12 · 3 of 7", design 10.5), the open question's kind and number,
 * the channel of the owner's latest input, the goal's checklist in brief, the records that the screen draws, when the
 * conversation was continued, its test mode (the Safety page's while no conversation is open, which the next one takes),
 * the quiet clock, and whether Kestrel keeps words until the owner is there
 * ([keptForOwner]: a goal's final reply, or a held goal's report, also after its conversation closed), for which plan
 * 7D's `ConversationSignals` keeps watching the phone. toString prints no words.
 */
data class ConversationView(
    val id: ConversationId? = null,
    val command: Int = 0,
    val goal: GoalPhase? = null,
    val step: Int? = null,
    val asking: QuestionKind? = null,
    val question: Int? = null,
    val replyChannel: InputChannel = InputChannel.VOICE,
    val checklist: ChecklistSummary? = null,
    val records: List<CommandRecord> = emptyList(),
    val continuedAtWall: Long? = null,
    val testMode: Boolean = true,
    val quiet: QuietClock = QuietClock.Off,
    val keptForOwner: Boolean = false,
) {
    /** The owner continued the conversation from the list or by name (D23). */
    val continued: Boolean get() = continuedAtWall != null

    override fun toString(): String =
        "ConversationView(open=${id != null}, command=$command, goal=$goal, step=$step, asking=$asking, records=${records.size}, quiet=$quiet, " +
            "kept=$keptForOwner)"
}

/** A goal's checklist for the bar and the notification (design 10.5 and 10.6): done of total, and the current item. */
data class ChecklistSummary(val done: Int, val total: Int, val current: String?) {
    override fun toString(): String = "ChecklistSummary(done=$done, total=$total, current=${current?.length})"

    companion object {
        fun of(checklist: Checklist): ChecklistSummary = ChecklistSummary(
            done = checklist.done,
            total = checklist.items.size,
            current = (checklist.items.firstOrNull { it.state == ItemState.DOING } ?: checklist.open.firstOrNull())?.text,
        )
    }
}

/** How command [command] ended, with Kestrel's final words, for the hook `agent-task -Result` (design 14.4). */
data class CommandOutcome(val command: Int, val report: TaskReport, val answer: String?) {
    override fun toString(): String = "CommandOutcome(command=$command, report=$report, answer=${answer?.length})"
}

/** What `restore()` found, for the hook `conversation-control do=restore-check` (design 14.4). */
enum class RestoreResult { NOTHING, OPEN, CLOSED, GOAL_HELD, GOAL_ENDED }
