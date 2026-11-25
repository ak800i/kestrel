package dev.kestrel.conversation.goal

import dev.kestrel.agent.TaskRequest
import dev.kestrel.agent.goal.GoalLimits
import dev.kestrel.agent.goal.ResumeCause
import dev.kestrel.agent.goal.ResumeNote
import dev.kestrel.agent.goal.Steer
import dev.kestrel.conversation.ConversationTexts
import dev.kestrel.conversation.context.ContextBlock
import dev.kestrel.conversation.model.CommandRecord
import dev.kestrel.conversation.model.LineKind
import dev.kestrel.conversation.store.RecordRef
import dev.kestrel.safety.Category
import java.time.ZoneId

/**
 * One round to run (design 7.1 and 7.6 of sub-project 7): the goal's [record] as it is now, the conversation's [earlier]
 * records (oldest first, the older ones condensed), the owner's [text] of a first round, the [resume] cause of a later
 * one, the conversation's fixed [testMode], when it was continued, the round's [limits], the debug hook's [options], the
 * goal's own record for the search ([self]), the phone's [zone], and how many of the record's steering lines still wait
 * in the goal's inbox ([waiting]), which the run takes before its first step. toString prints no words.
 */
class RoundPlan(
    val record: CommandRecord,
    val earlier: List<CommandRecord>,
    val text: String,
    val resume: ResumeCause?,
    val testMode: Boolean,
    val continuedAtWall: Long?,
    val limits: GoalLimits,
    val options: Map<String, String>,
    val self: RecordRef,
    val zone: ZoneId,
    val waiting: Int = 0,
) {
    override fun toString(): String = "RoundPlan(record=${record.number}, earlier=${earlier.size}, resume=$resume, testMode=$testMode, waiting=$waiting)"
}

/**
 * A round's `TaskRequest` (design 7.1, 7.6, 8.4 and 12.1 of sub-project 7; plan 7A's "Interfaces for 7B"). A first round
 * gets the owner's words, the records block of the earlier commands, their owner's words (requests, answers and steering)
 * and their allowance. A resumed round's text is the goal's first command as its record holds it, with its checklist and
 * its ledger in a `ResumeNote`, with every steer that the goal's earlier rounds took, so that a steer that an
 * interruption cut before the model acted on it still reaches the model as an instruction; its owner's words and
 * allowance add the goal's own inputs (answers, steering and
 * "continue") and its steering's allowance, because a new run starts a new `TaskSafety`. The block never holds the goal's
 * own record. A steering message that resumes the goal reaches the run through its inbox instead.
 */
object GoalRequests {
    fun request(plan: RoundPlan, taskId: String, knownNames: Set<String>, budget: Int): TaskRequest {
        val note = plan.continuedAtWall?.let { ConversationTexts.continuedNote(it, plan.zone, plan.testMode) }
        val block = ContextBlock.build(plan.earlier, budget, plan.zone, note)
        val words = ownerWords(plan.earlier)
        val carried = categories(plan.earlier.flatMap { it.allowance })
        val resume = plan.resume
            ?: return TaskRequest(taskId, plan.text, knownNames, block, words, carried, limits = plan.limits)
        return TaskRequest(
            id = taskId,
            text = plan.record.request,
            knownNames = knownNames,
            conversation = block,
            ownerHistory = words + goalInputs(plan.record),
            carried = carried + categories(plan.record.allowance),
            checklist = plan.record.checklist,
            resume = ResumeNote(resume, plan.record.ledger, steered(plan.record, plan.waiting)),
            limits = plan.limits,
        )
    }

    /** The owner's words of [records]: each request, and its answers and steering, also steering that came too late (D8). */
    fun ownerWords(records: List<CommandRecord>): List<String> = records.flatMap { record ->
        listOf(record.request) + record.lines.filter { it.kind in WORDS }.map { it.text }
    }

    /** The goal's steering that its earlier rounds took, oldest first: its record's steering but the last [waiting]. */
    fun steered(record: CommandRecord, waiting: Int): List<Steer> =
        record.lines.filter { it.kind == LineKind.OWNER_STEERED }.dropLast(waiting).map { line ->
            Steer(line.text, requireNotNull(line.channel) { "A steering line names its channel" }.toSteer())
        }

    /** A goal's own inputs after its first command: its answers, its steering and its "continue" inputs. */
    fun goalInputs(record: CommandRecord): List<String> = record.lines.filter { it.kind.owners }.map { it.text }

    /** The categories that a record's allowance names by their wire names (D16). */
    fun categories(wires: Collection<String>): Set<Category> = wires.mapNotNullTo(mutableSetOf()) { wire -> Category.entries.firstOrNull { it.wire == wire } }

    // The kinds of an earlier record's lines that hold the owner's words for later commands; "continue" is no words of his.
    private val WORDS = setOf(LineKind.OWNER_ANSWERED, LineKind.OWNER_STEERED, LineKind.OWNER_TOO_LATE)
}
