package dev.kestrel.eval.report

import java.util.Locale

/** A task's passes of its runs; its rate is passes ÷ runs. */
data class Rate(val passes: Int, val runs: Int) {
    val value: Double get() = if (runs == 0) 0.0 else passes.toDouble() / runs

    operator fun plus(other: Rate) = Rate(passes + other.passes, runs + other.runs)
}

/**
 * A device's judgement (design D10 of sub-project 6): its [rate] and the [baseline]'s, in percent, over the [shared]
 * tasks; whether it [passed]; the tasks whose rate [fell] below the baseline's, which the re-runs run again; the tasks
 * that fell from 3 of 3 to 1 of 3 or less in their first runs ([collapsed]), which are always reported; and the tasks
 * that ran only in the results or only in the baseline.
 */
data class Judgement(
    val device: String,
    val shared: List<String>,
    val rate: Double,
    val baseline: Double,
    val passed: Boolean,
    val fell: List<String>,
    val collapsed: List<String>,
    val onlyResults: List<String>,
    val onlyBaseline: List<String>,
) {
    fun lines(): List<String> = buildList {
        add("compare=$device result=${if (passed) "pass" else "fail"} rate=${percent(rate)} baseline=${percent(baseline)} tasks=${shared.size}")
        if (fell.isNotEmpty()) add("compare=$device fell=${fell.joinToString(",")}")
        if (collapsed.isNotEmpty()) add("compare=$device collapsed=${collapsed.joinToString(",")}")
        if (onlyResults.isNotEmpty()) add("compare=$device onlyResults=${onlyResults.joinToString(",")}")
        if (onlyBaseline.isNotEmpty()) add("compare=$device onlyBaseline=${onlyBaseline.joinToString(",")}")
    }

    private fun percent(value: Double) = String.format(Locale.ROOT, "%.1f", value)
}

/**
 * Design D10's rule: a device's rate is the mean of its tasks' rates over the tasks that ran in both the results and
 * its baseline part, without known gaps, so that a task with 6 runs weighs as much as one with 3; the device fails when
 * its rate is more than [POINTS] percentage points below the baseline's over the same tasks. [first] are the rates of
 * the first runs, before any re-run, which decide the always-reported tasks.
 */
object Comparison {
    const val POINTS = 5.0

    fun judge(device: String, results: Map<String, Rate>, part: BaselinePart, gaps: Set<String>, first: Map<String, Rate> = results): Judgement {
        val ran = results.filterValues { it.runs > 0 }.keys - gaps
        val recorded = part.tasks.filterValues { it.runs > 0 }.keys - gaps
        val shared = (ran intersect recorded).sorted()
        fun base(task: String) = part.tasks.getValue(task).let { Rate(it.passes, it.runs) }
        val rate = if (shared.isEmpty()) 0.0 else shared.map { results.getValue(it).value }.average() * 100
        val baseline = if (shared.isEmpty()) 0.0 else shared.map { base(it).value }.average() * 100
        return Judgement(
            device = device,
            shared = shared,
            rate = rate,
            baseline = baseline,
            passed = shared.isNotEmpty() && baseline - rate <= POINTS + 1e-9,
            fell = shared.filter { results.getValue(it).value < base(it).value },
            collapsed = shared.filter { task -> base(task).let { it.runs == 3 && it.passes == 3 } && (first[task] ?: results.getValue(task)).passes <= 1 },
            onlyResults = (ran - recorded).sorted(),
            onlyBaseline = (recorded - ran).sorted(),
        )
    }
}
