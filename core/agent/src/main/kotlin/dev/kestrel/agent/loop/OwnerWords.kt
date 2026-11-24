package dev.kestrel.agent.loop

import dev.kestrel.agent.TaskRequest
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.safety.TaskAllowance
import dev.kestrel.safety.TaskSafety

/**
 * What the owner's own words give a run (D13 of sub-project 5; D8 and D16 of sub-project 7): they count as his text, so
 * typing them is typing the request, and the verbs of his commands make the allowance. The owner's words of the
 * conversation's earlier inputs count too, and the allowance of its earlier commands carries; Standard mode still confirms
 * every send, share and delete, and every call is still handed over.
 */
internal object OwnerWords {
    /** The run's first safety: [task]'s words and allowance, with [names] the names that the allowance reads. */
    fun seeded(task: TaskRequest, names: Set<String>, memoryValues: List<String>): TaskSafety = task.ownerHistory.fold(
        TaskSafety(
            allowance = TaskAllowance.from(task.text, names) + TaskAllowance.carried(task.carried),
            ownerWords = Normalizer.words(task.text).toSet(),
            memoryValues = memoryValues,
            ownerDigits = listOf(TaskSafety.digitsOf(task.text)).filter { it.isNotEmpty() },
        ),
    ) { seeded, words -> seeded.withOwnerWords(words) }
}
