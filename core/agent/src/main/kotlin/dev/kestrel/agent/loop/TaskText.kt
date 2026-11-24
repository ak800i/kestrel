package dev.kestrel.agent.loop

import dev.kestrel.agent.TaskRequest
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
     * code; the [allowance]'s sentence and a routine's [note] are built from parts that were redacted already.
     */
    fun message(task: TaskRequest, date: String, allowance: TaskAllowance, note: String?, redactor: Redactor): Redacted {
        val lead = if (task.conversation == null) "The owner's task: " else "The owner's new command: "
        val owner = Redacted.of(redactor, lead + task.text).text
        return Redacted.kestrel(listOfNotNull(owner, "Today is $date.", allowance.describe(), note).joinToString("\n"))
    }
}
