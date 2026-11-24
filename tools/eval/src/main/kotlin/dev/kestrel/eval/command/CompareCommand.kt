package dev.kestrel.eval.command

import dev.kestrel.eval.device.Refusal
import dev.kestrel.eval.report.Baseline
import dev.kestrel.eval.report.Comparison
import dev.kestrel.eval.report.Rate
import dev.kestrel.eval.report.Report
import dev.kestrel.eval.task.Catalogue
import java.util.Locale

/**
 * `compare <results folder>… [--rerun --device <serial>]` (design D2 and D10 of sub-project 6): each folder's live
 * `report.json` judged against its device's part of `eval\baseline.json`, one `compare=<device> …` line per finding, and
 * with folders of both devices the total of both. With `--rerun`, a device that fails runs the tasks whose rate fell 3
 * more times live ([rerun], a new folder beside the first), and is judged again on the 6 runs. Results of another model
 * than the device's part are refused, with the way to record a new part. Returns 0 when every device passed, else 1.
 */
class CompareCommand(
    private val environment: Environment,
    private val command: Command.Compare,
    private val rerun: ((String, List<String>) -> Report)? = null,
) {
    fun execute(): Int {
        val baseline = Baseline.read(environment.root.resolve("eval").resolve("baseline.json"))
        val reports = command.folders.map { Report.read(environment.root.resolve(it).resolve("report.json")) }
        var failed = false
        for (report in reports) {
            if (!report.live) throw Refusal("compare judges live results only, and ${report.device}'s were not live.")
            val part = baseline.devices[report.device]
                ?: throw Refusal("eval\\baseline.json has no part for ${report.device}; record one with scripts\\eval.ps1 suite --device <serial> --live --record-baseline.")
            if (part.model != report.model) {
                throw Refusal("${report.device}'s results used the model ${report.model}, and its baseline part ${part.model}; record a new part with scripts\\eval.ps1 suite --device <serial> --live --record-baseline.")
            }
            val first = report.tasks.associate { it.task to Rate(it.passes, it.runs) }
            var judgement = Comparison.judge(report.device, first, part, GAPS)
            val device = command.device
            if (!judgement.passed && command.rerun && device != null && judgement.fell.isNotEmpty()) {
                val again = (rerun ?: ::liveRerun)(device, judgement.fell)
                val more = again.tasks.associate { it.task to Rate(it.passes, it.runs) }
                judgement = Comparison.judge(report.device, first.mapValues { (task, rate) -> more[task]?.let { rate + it } ?: rate }, part, GAPS, first)
            }
            judgement.lines().forEach { environment.out.println(it) }
            if (!judgement.passed) failed = true
        }
        if (reports.size > 1) {
            val credits = String.format(Locale.ROOT, "%.2f", reports.sumOf { it.totals.credits })
            environment.out.println("compare=total passes=${reports.sumOf { it.totals.passes }} runs=${reports.sumOf { it.totals.runs }} credits=$credits")
        }
        return if (failed) 1 else 0
    }

    /** The re-runs: [tasks] 3 more times live on [serial], into a new results folder, which must name the same device. */
    private fun liveRerun(serial: String, tasks: List<String>): Report {
        val series = SeriesCommand(environment, "suite", live = true, runs = 3) { kind -> Catalogue.suite(kind, live = true).filter { it.name in tasks } }
        series.execute(serial) {}
        return series.report ?: throw IllegalStateException("The re-runs wrote no report.")
    }

    private companion object {
        val GAPS = Catalogue.ALL.filter { it.gap != null }.map { it.name }.toSet()
    }
}
