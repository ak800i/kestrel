package dev.kestrel.eval.run

import dev.kestrel.eval.hook.HookAnswer

/**
 * A run's detail line (plan 6B-basefix), which the runner writes after the run's [RunLine], on the console and into a
 * suite's `details.txt`: the run's answers to the task's questions ([asked]), the owner's touches and the signals among
 * them that Kestrel counted (from agent-task's answer at the task's [end]), whether a `say` text and a data tool's result
 * held the run's marker (agent-task's answer with the marker, [markers]), and the settings that a restore found changed
 * and put back, in effect again ([applied], `putBack=`) or stored only ([stored], `byHand=`). Kinds, counts, flags and
 * setting names only, never text; `none` where the hooks did not answer or nothing was there.
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
        return "task=$task run=$run asked=${list(asked)} ownerTouches=${field(end, "ownerTouches")} signals=${field(end, "signals")} " +
            "markerSaid=${field(markers, "markerSaid")} markerInData=${field(markers, "markerInData")} putBack=${list(applied)} byHand=${list(stored)}"
    }
}
