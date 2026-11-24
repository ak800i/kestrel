package dev.kestrel.eval.run

import dev.kestrel.eval.hook.HookAnswer

/**
 * A run's detail line (plan 6B-basefix), which [LineWriter] writes with the run's [RunLine], on the console and into a
 * suite's `details.txt`: the run's answers to the task's questions ([asked]), the owner's touches and the signals among
 * them that Kestrel counted (from agent-task's answer at the task's [end]), whether a `say` text and a data tool's result
 * held the run's marker (agent-task's answer with the marker, [markers]), and the settings that a restore found changed
 * and put back, in effect again ([applied], `putBack=`) or stored only ([stored], `byHand=`). Kinds, counts, flags and
 * setting names only, never text; `none` where the hooks did not answer or nothing was there. The line begins with
 * `detail `, so that no reader of run lines (`task=<name> run=<i> `, as a71-agent.ps1's `ConvertFrom-TaskLine`) takes it
 * for one.
 */
data class RunDetail(
    val task: String,
    val run: Int,
    val asked: List<Asked>,
    val end: HookAnswer?,
    val markers: HookAnswer?,
    val applied: List<String>,
    val stored: List<String>,
) {
    fun text(): String {
        fun list(items: List<Any>) = items.joinToString(",").ifEmpty { "none" }
        fun field(answer: HookAnswer?, name: String) = answer?.get(name) ?: "none"
        return "$PREFIX task=$task run=$run asked=${list(asked)} ownerTouches=${field(end, "ownerTouches")} signals=${field(end, "signals")} " +
            "markerSaid=${field(markers, "markerSaid")} markerInData=${field(markers, "markerInData")} putBack=${list(applied)} byHand=${list(stored)}"
    }

    companion object {
        private const val PREFIX = "detail"
        private val RUN = Regex("^$PREFIX task=(\\S+) run=(\\d+) ")

        /** The detail line of run [run] of [task] when the run never started, or ended before anything was known. */
        fun empty(task: String, run: Int) = RunDetail(task, run, emptyList(), null, null, emptyList(), emptyList())

        /** The task and the run that a detail line's [text] names, or null for a text that is no detail line. */
        fun runOf(text: String): Pair<String, Int>? = RUN.find(text)?.let { it.groupValues[1] to it.groupValues[2].toInt() }
    }
}