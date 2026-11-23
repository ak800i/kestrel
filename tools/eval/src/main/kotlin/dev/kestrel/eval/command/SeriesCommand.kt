package dev.kestrel.eval.command

import dev.kestrel.eval.device.DeviceKind
import dev.kestrel.eval.device.Refusal
import dev.kestrel.eval.report.Report
import dev.kestrel.eval.report.ResultsFile
import dev.kestrel.eval.run.LineWriter
import dev.kestrel.eval.run.RunOptions
import dev.kestrel.eval.run.ScriptInstaller
import dev.kestrel.eval.task.Catalogue
import dev.kestrel.eval.task.InjectionFixtures
import dev.kestrel.eval.task.Task
import java.nio.file.Path
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.seconds

/**
 * A series of tasks into a new results folder `eval-results\<device>[-injection]-<yyyyMMdd-HHmmss>\` (design D6 and D10
 * of sub-project 6): `runs.txt` in `Add-SessionResult`'s form, with the line `<task> on <the A71|kestrel-api37>, <n> runs`
 * before each task's run lines, which a71-agent.ps1's `Get-SittingRuns` and `ConvertFrom-TaskLine` read, and
 * `report.json`. `suite` runs the suite of the device (3 runs each by default); `injection` the 14 fixtures on
 * kestrel-api37 (once without a model, 5 times live, by default). Returns 0 when no run failed, else 1.
 */
class SeriesCommand(
    private val environment: Environment,
    private val name: String,
    private val live: Boolean,
    private val runs: Int,
    private val pick: (DeviceKind) -> List<Task>,
) {
    fun execute(serial: String, lines: (LineWriter) -> Unit): Int {
        val session = Session(environment, serial)
        val kind = session.device.kind
        val tasks = pick(kind)
        if (tasks.isEmpty()) throw Refusal("The command $name has no task for ${kind.label}.")
        val stamp = environment.clock.now().format(STAMP)
        val folder: Path = environment.root.resolve("eval-results").resolve("${kind.folder}${if (name == "injection") "-injection" else ""}-$stamp")
        val runsFile = ResultsFile(folder.resolve("runs.txt"), environment.clock)
        val writer = LineWriter(session.console, runsFile)
        lines(writer)
        val start = environment.clock.now()
        return session.guarded {
            session.prepare(tasks, live)
            val runner = session.runner(RunOptions(live), writer)
            var failures = 0
            for (task in tasks) {
                val count = task.runsAtMost?.let { minOf(it, runs) } ?: runs
                runsFile.append(name, "${task.name} on ${kind.label}, $count runs")
                failures += runner.runAll(task, if (live) null else ScriptInstaller.resource(task.dryRun!!.script), runs)
            }
            Report.of(kind, commit(), live, start, environment.clock.now(), writer.lines).write(folder.resolve("report.json"))
            session.console.line("The results are in $folder.")
            if (failures > 0) 1 else 0
        }
    }

    private fun commit(): String = try {
        environment.processes.run(listOf("git", "-C", environment.root.toString(), "rev-parse", "HEAD"), 30.seconds).out.trim().ifEmpty { "unknown" }
    } catch (e: Exception) {
        "unknown"
    }

    companion object {
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        fun suite(environment: Environment, command: Command.Suite): SeriesCommand =
            SeriesCommand(environment, "suite", command.live, command.runs) { kind ->
                val suite = Catalogue.suite(kind, command.live)
                val unknown = command.tasks.orEmpty().filter { name -> suite.none { it.name == name } }
                if (unknown.isNotEmpty()) throw Refusal("The suite of ${kind.label} has no task ${unknown.joinToString()}.")
                command.tasks?.let { names -> suite.filter { it.name in names } } ?: suite
            }

        fun injection(environment: Environment, command: Command.Injection): SeriesCommand =
            SeriesCommand(environment, "injection", command.live, command.runs ?: if (command.live) 5 else 1) { kind ->
                if (kind != DeviceKind.EMULATOR) throw Refusal("The injection fixtures run on kestrel-api37 only.")
                InjectionFixtures.ALL
            }
    }
}
