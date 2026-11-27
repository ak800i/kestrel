package dev.kestrel.eval.run

import dev.kestrel.eval.hook.HookAnswer
import java.util.Locale

/**
 * The run's commands' ends as one agent-task answer (design 14.4 of sub-project 7), so that a run's line keeps
 * task-check.ps1's form whatever the number of its commands: the end is the first end of an earlier command that its turn
 * did not expect, else the last command's, which the task's verdict judges; the steps, the working time, the AI Credits,
 * the counts of confirmed and unconfirmed actions, shared items and test mode's decisions, the tokens, and the owner's
 * touches and signals are sums; the model is the last that a command named. A run of one command keeps its command's
 * answer as it is. Null while a command's end is not read, as for a run of one command whose end the run did not read.
 */
object Outcomes {
    private val SUMS = listOf("steps", "workMs", "confirmed", "unconfirmed", "shareItems", "beforeTestMode", "in", "out", "cacheRead", "cacheWrite", "ownerTouches", "signals")

    fun merge(commands: List<SentCommand>): HookAnswer? {
        if (commands.any { it.open }) return null
        val ended = commands.mapNotNull { command -> command.answer?.let { command to it } }
        if (ended.size <= 1) return ended.singleOrNull()?.second
        val unexpected = ended.dropLast(1).firstOrNull { (command, answer) -> command.expected != null && answer["end"] != command.expected }
        val end = (unexpected ?: ended.last()).second["end"] ?: "none"
        val answers = ended.map { it.second }
        val credits = String.format(Locale.ROOT, "%.2f", answers.sumOf { it["credits"]?.toDoubleOrNull() ?: 0.0 })
        val model = answers.mapNotNull { it["model"] }.lastOrNull { it != "none" } ?: "none"
        val sums = SUMS.joinToString(" ") { field -> "$field=${answers.sumOf { it[field]?.toLongOrNull() ?: 0L }}" }
        return HookAnswer("state=done end=$end credits=$credits model=$model $sums")
    }
}
