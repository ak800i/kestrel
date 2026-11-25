package dev.kestrel.agent.loop

import dev.kestrel.agent.OwnerLines
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
 * do, and a failed routine's note, each owner's line built by `OwnerLines`.
 */
internal object TaskText {
    const val TEST_MODE_NOTE = "Test mode is on: Kestrel sends only to the owner's own chats, and it never calls anyone."


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
     * The owner's words of [task] as the run carries them (design 8.2 of sub-project 7): a command's fresh words, redacted
     * here, once; or a resumed goal's first command as its record holds it, which was redacted once when it entered its
     * first run, so never again (design 7.6).
     */
    fun request(task: TaskRequest, redactor: Redactor): Redacted =
        if (task.resume == null) Redacted.of(redactor, task.text) else Redacted.recorded(task.text)

    /**
     * The task message of [task], whose owner's words are [request], at [date]. His words are on their own line, quoted
     * and escaped and never cut; the [allowance]'s sentence and a routine's [note] are built from parts that were redacted
     * already. A resumed goal's message starts with Kestrel's own lines instead (design 7.6 of sub-project 7).
     */
    fun message(task: TaskRequest, request: Redacted, date: String, allowance: TaskAllowance, note: String?): Redacted {
        val resume = task.resume
        val opening = when {
            resume != null -> resumed(task.checklist, request, resume)
            task.conversation == null -> listOf(OwnerLines.task(request.text))
            else -> listOf(OwnerLines.newCommand(request.text))
        }
        return Redacted.kestrel((opening + listOfNotNull("Today is $date.", allowance.describe(), note)).joinToString("\n"))
    }

    // A resumed goal's own lines (design 7.6 of sub-project 7): the goal, why it resumes, its checklist, what Kestrel did,
    // the owner's steering of its earlier rounds, and a fresh look. The checklist's texts came from the model and the
    // ledger's lines hold labels from the screen and contacts' names, so both are quoted as data, and no line break in
    // them can start a line of Kestrel's own; the goal and the steering are the owner's own words, whole, in their
    // record's form, which was redacted once already.
    private fun resumed(checklist: Checklist?, goal: Redacted, resume: ResumeNote): List<String> =
        listOfNotNull(
            OwnerLines.goal(goal.text),
            "You are continuing this goal after ${resume.after.words}.",
            checklist?.let(::checklistLine),
            doneLine(resume.done),
        ) + resume.steered.map { OwnerLines.added(it.channel, EARLIER_ROUND, it.text) } + "Look at the screen afresh; it may have changed."

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
     * with [widened], the allowance's sentence, on a line of its own when the steer widened the allowance. His fresh words
     * are redacted here, once, and quoted and escaped, never cut, as his command's are.
     */
    fun steered(steer: Steer, step: Int, widened: String?, redactor: Redactor): Redacted {
        val at = if (step == 0) "before the first step" else "at step $step"
        val line = OwnerLines.added(steer.channel, at, Redacted.of(redactor, steer.text).text)
        return Redacted.kestrel(listOfNotNull(line, widened).joinToString("\n"))
    }

    private const val EARLIER_ROUND = "in an earlier round"
}