package dev.kestrel.agent.loop

import dev.kestrel.agent.TaskRequest
import dev.kestrel.agent.goal.Checklist
import dev.kestrel.agent.goal.ChecklistItem
import dev.kestrel.agent.goal.ChecklistRules
import dev.kestrel.agent.goal.ChecklistText
import dev.kestrel.agent.goal.ResumeNote
import dev.kestrel.agent.goal.Steer
import dev.kestrel.agent.observe.ScreenText
import dev.kestrel.safety.Redactor
import dev.kestrel.safety.TaskAllowance

/**
 * A run's own texts, which never change within the run (design D3 of sub-project 5; design 8.4 and 8.5 of sub-project
 * 7). The context holds first what every command of a conversation shares, so that the providers' automatic prefix
 * caches reuse it from one command to the next: the test-mode note, then the conversation's records block, then the
 * memory that this command names. The task message holds the owner's words, the local date and time, what the run may
 * do, and a failed routine's note; in a later command of a conversation it starts "The owner's new command:".
 */
internal object TaskText {
    const val TEST_MODE_NOTE = "Test mode is on: Kestrel sends only to the owner's own chats, and it never calls anyone."

    /** How an owner's message while the goal acts starts (design 7.3 of sub-project 7); the system prompt names it. */
    const val OWNER_ADDED = "The owner added"

    /**
     * The context of [task]: the test-mode note when [testMode] is on, the records block as it came (each of its parts was
     * redacted once, when it entered its run), and [memory], redacted here; null when there is none. It never names the
     * owner's agent-free apps (review B M6 of sub-project 5).
     */
    fun context(task: TaskRequest, testMode: Boolean, memory: String?, redactor: Redactor): Redacted? {
        val parts = listOfNotNull(TEST_MODE_NOTE.takeIf { testMode }, task.conversation?.text, memory?.let(redactor::redact))
        return parts.takeIf { it.isNotEmpty() }?.let { Redacted.kestrel(it.joinToString("\n\n")) }
    }

    /**
     * The task message of [task] at [date]. Only the owner's words are redacted here, so that the year is never read as a
     * code; the [allowance]'s sentence and a routine's [note] are built from parts that were redacted already. A resumed
     * goal's message starts with Kestrel's own lines instead (design 7.6 of sub-project 7).
     */
    fun message(task: TaskRequest, date: String, allowance: TaskAllowance, note: String?, redactor: Redactor): Redacted {
        val resume = task.resume
        val opening = if (resume == null) {
            val lead = if (task.conversation == null) "The owner's task: " else "The owner's new command: "
            listOf(Redacted.of(redactor, lead + task.text).text)
        } else {
            resumed(task, resume, redactor)
        }
        return Redacted.kestrel((opening + listOfNotNull("Today is $date.", allowance.describe(), note)).joinToString("\n"))
    }

    // A resumed goal's own lines (design 7.6 of sub-project 7): the goal, why it resumes, its checklist, what Kestrel did,
    // the owner's steering of its earlier rounds, and a fresh look. The checklist's texts came from the model and the
    // ledger's lines hold labels from the screen and contacts' names, so both are quoted as data, and no line break in
    // them can start a line of Kestrel's own; the steering is the owner's own words, whole.
    private fun resumed(task: TaskRequest, resume: ResumeNote, redactor: Redactor): List<String> =
        listOfNotNull(
            Redacted.of(redactor, "The owner's goal: " + task.text).text,
            "You are continuing this goal after ${resume.after.words}.",
            task.checklist?.let(::checklistLine),
            doneLine(resume.done),
        ) + resume.steered.map { added(it, "in an earlier round", redactor) } + "Look at the screen afresh; it may have changed."

    // Kestrel's notes of what it did, each quoted whole, because a cut note could name fewer recipients than it reached.
    private fun doneLine(done: List<String>): String =
        if (done.isEmpty()) "What Kestrel did so far: nothing yet."
        else "What Kestrel did so far, in its own notes: " + done.joinToString(" · ") { ScreenText.quoteWhole(it) }

    // A checklist of more than 20 items names only its open items, at most 20, and counts its closed ones as a records
    // block does; they stay as they are and need no id from the model (design 7.2 and 8.3 of sub-project 7).
    private fun checklistLine(checklist: Checklist): String {
        fun named(items: List<ChecklistItem>) = items.joinToString(" · ") { "${it.id} [${it.state.wire}] ${ScreenText.quote(it.text, ChecklistRules.TEXT_LIMIT)}" }
        if (checklist.items.size <= ChecklistRules.MAX_ITEMS) return "Its checklist: " + named(checklist.items)
        return "Its checklist: ${ChecklistText.closed(checklist)}; open: " + named(checklist.open).ifEmpty { "none" }
    }

    /**
     * The owner's [steer] after step [step], or before the first step when [step] is 0 (design 7.3 of sub-project 7),
     * with [widened], the allowance's sentence, on a line of its own when the steer widened the allowance. His words are
     * redacted, quoted and escaped, and never cut, as his command's words in the task message are not.
     */
    fun steered(steer: Steer, step: Int, widened: String?, redactor: Redactor): Redacted {
        val at = if (step == 0) "before the first step" else "at step $step"
        return Redacted.kestrel(listOfNotNull(added(steer, at, redactor), widened).joinToString("\n"))
    }

    // An owner's steer [at] a point of the goal, as the model reads it: his words redacted once, quoted and escaped, and
    // never cut (design 7.3 of sub-project 7).
    private fun added(steer: Steer, at: String, redactor: Redactor): String =
        "$OWNER_ADDED (${steer.channel.wire}) $at: ${ScreenText.quoteWhole(Redacted.of(redactor, steer.text).text)}"
}
