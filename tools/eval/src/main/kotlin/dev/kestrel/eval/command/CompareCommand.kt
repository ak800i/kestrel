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
 * with folders of both devices the total of both. Only suites of D10's 3 runs per task are judged; a known gap may run
 * fewer. With `--rerun`, a device that fails prints its first judgement, runs the tasks whose rate fell 3 more times
 * live ([rerun], a new folder beside the first), and is judged again on the 6 runs; re-runs on another device than the
 * folder's, or of another model than the device's part, are refused. Results of another model than the device's part
 * are refused, with the way to record a new part. A report whose egress check failed or was not judged, the first or the
 * re-runs', fails its device too, and so does one without an egress result, because every live series captures, so
 * such a report comes from a series that was never captured. Returns 0 when every device passed, else 1.
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
            // The main spec's 16.5: a folder of the multi-turn tasks is judged against its device's multi-turn part only.
            val parts = if (report.multiTurn) baseline.multiTurn else baseline.devices
            val part = parts[report.device] ?: throw Refusal("eval\\baseline.json has no ${partName(report)} for ${report.device}; record one with ${record(report)}.")
            val label = if (report.multiTurn) "${report.device}-multi-turn" else report.device
            refuseOtherModel(report, part.model)
            val other = report.tasks.firstOrNull { it.task !in GAPS && it.runs + it.skipped.values.sum() != Comparison.RUNS }
            if (other != null) {
                throw Refusal(
                    "compare judges suites of design D10's ${Comparison.RUNS} runs per task, and ${report.device}'s results ran ${other.task} " +
                        "${other.runs + other.skipped.values.sum()} times.",
                )
            }
            val first = report.tasks.associate { it.task to Rate(it.passes, it.runs) }
            if (egressFailed(report)) failed = true
            var judgement = Comparison.judge(label, first, part, GAPS)
            val device = command.device
            if (!judgement.passed && command.rerun && device != null && judgement.fell.isNotEmpty()) {
                // Shown before the re-runs, so that a refusal or Ctrl+C during them still leaves the first judgement.
                judgement.lines().forEach { environment.out.println(it) }
                environment.out.println("compare=$label rerun=${judgement.fell.joinToString(",")} runs=${Comparison.RUNS}")
                val again = rerun?.invoke(device, judgement.fell) ?: liveRerun(device, report.device, judgement.fell, report.multiTurn)
                if (again.device != report.device) throw refusedDevice(again.device, report.device)
                refuseOtherModel(again, part.model)
                if (egressFailed(again)) failed = true
                val more = again.tasks.associate { it.task to Rate(it.passes, it.runs) }
                judgement = Comparison.judge(label, first.mapValues { (task, rate) -> more[task]?.let { rate + it } ?: rate }, part, GAPS, first)
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

    /**
     * The re-runs: [tasks] 3 more times live on [serial], into a new results folder, of the suite's or of the multi-turn
     * tasks ([multiTurn]). A device of another kind than [folder]'s is refused before any run.
     */
    private fun liveRerun(serial: String, folder: String, tasks: List<String>, multiTurn: Boolean): Report {
        val series = SeriesCommand(environment, SeriesCommand.SUITE, live = true, runs = 3, multiTurn = multiTurn) { kind ->
            if (kind.folder != folder) throw refusedDevice(kind.folder, folder)
            (if (multiTurn) Catalogue.multiTurn(kind, live = true) else Catalogue.suite(kind, live = true)).filter { it.name in tasks }
        }
        series.execute(serial) {}
        return series.report ?: throw IllegalStateException("The re-runs wrote no report.")
    }

    private fun refusedDevice(device: String, folder: String) =
        Refusal("compare --rerun's device is $device, and the results folder's is $folder; give --device the serial of $folder.")

    /**
     * Whether [report]'s egress check (design D11) failed, was not judged, or is missing, which fails the comparison
     * whatever the rates: [execute] judges live reports only, and a live report without the check's result is unknown.
     */
    private fun egressFailed(report: Report): Boolean {
        val egress = report.egress
        if (egress == null) {
            environment.out.println("compare=${report.device} egress=unknown")
            return true
        }
        if (egress.passed) return false
        environment.out.println("compare=${report.device} egress=fail connections=${egress.connections} failures=${egress.failures} unknown=${egress.unknown}")
        return true
    }

    /** Results, the first or the re-runs', of another model than the device's part, which D10 does not compare. */
    private fun refuseOtherModel(report: Report, model: String) {
        if (report.model != model) {
            throw Refusal("${report.device}'s results used the model ${report.model}, and its baseline ${partName(report)} $model; record a new one with ${record(report)}.")
        }
    }

    /** The baseline's part that [report] is judged against: the device's part, or its multi-turn part. */
    private fun partName(report: Report) = if (report.multiTurn) "multi-turn part" else "part"

    /** The command that records that part. */
    private fun record(report: Report) = "scripts\\eval.ps1 suite --device <serial> --live${if (report.multiTurn) " --multi-turn" else ""} --record-baseline"

    private companion object {
        val GAPS = Catalogue.ALL.filter { it.gap != null }.map { it.name }.toSet()
    }
}
