package dev.kestrel.agent

import dev.kestrel.agent.goal.Checklist
import dev.kestrel.agent.goal.GoalLimits
import dev.kestrel.agent.goal.ResumeNote
import dev.kestrel.agent.loop.Redacted
import dev.kestrel.safety.Category

/**
 * One task run as the owner gave it (design D1 and D3 of sub-project 5; design 5.4 of sub-project 7). [id] is
 * `task-<n>-<8 hex characters>`, which `:app` makes and which also names the task in the activity log and the network log
 * (D25). [knownNames] are the contact names that `:app` read on the phone when Kestrel has the contacts permission, for
 * the task's allowance (D13); no screen text is among them.
 *
 * Within a conversation (sub-project 7): [conversation] is the records block of its earlier commands, which goes into the
 * context after the test-mode note (design 8.4); [ownerHistory] are the owner's words of its earlier commands, answers
 * and steering, which count as his own text (D8); [carried] is the allowance of its earlier commands, of which only the
 * categories of `TaskAllowance.CARRIES` count (D16); [checklist] and [resume] are set when a goal resumes in a new run
 * (design 7.6); and [limits] are one round's limits (D7). toString prints only lengths and counts (D25).
 */
data class TaskRequest(
    val id: String,
    val text: String,
    val knownNames: Set<String> = emptySet(),
    val conversation: Redacted? = null,
    val ownerHistory: List<String> = emptyList(),
    val carried: Set<Category> = emptySet(),
    val checklist: Checklist? = null,
    val resume: ResumeNote? = null,
    val limits: GoalLimits = GoalLimits(),
) {
    override fun toString(): String =
        "TaskRequest(idLength=${id.length}, textLength=${text.length}, knownNames=${knownNames.size}, " +
            "conversation=${conversation?.text?.length}, ownerHistory=${ownerHistory.size}, carried=${carried.sorted()}, " +
            "checklist=$checklist, resume=$resume, limits=$limits)"

    companion object {
        /** The first line of every [conversation] block, which the system prompt names (design 8.4 of sub-project 7). */
        const val CONVERSATION_HEADER = "This conversation so far:"
    }
}