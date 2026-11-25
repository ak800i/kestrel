package dev.kestrel.agent.loop

import dev.kestrel.agent.TaskRequest
import dev.kestrel.safety.OwnerText
import dev.kestrel.safety.TaskAllowance
import dev.kestrel.safety.TaskSafety

/**
 * What the owner's own words give a run (D13 of sub-project 5; D8 and D16 of sub-project 7): they are the run's
 * `TaskSafety.owner`, the one record of his words that every safety rule reads, so typing them is typing the request and a
 * link that he named is his; and the verbs of his commands make the allowance. The owner's words of the conversation's
 * earlier inputs count too, and the allowance of its earlier commands carries; Standard mode still confirms every send,
 * share and delete, and every call is still handed over.
 */
internal object OwnerWords {
    /** The run's first safety: [task]'s words and allowance, with [names] the names that the allowance reads. */
    fun seeded(task: TaskRequest, names: Set<String>, memoryValues: List<String>): TaskSafety = TaskSafety(
        allowance = TaskAllowance.from(task.text, names) + TaskAllowance.carried(task.carried),
        owner = OwnerText.of(listOf(task.text) + task.ownerHistory),
        memoryValues = memoryValues,
    )

    /**
     * [safety] after the owner's steering [text] while the goal acts (design 7.3 of sub-project 7): his words, and his
     * command's verbs joined to the allowance, because steering is his command.
     */
    fun steered(safety: TaskSafety, text: String, names: Set<String>): TaskSafety =
        safety.withOwnerWords(text).copy(allowance = safety.allowance + TaskAllowance.from(text, names))
}
