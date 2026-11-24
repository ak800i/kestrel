package dev.kestrel.eval.command

import dev.kestrel.eval.device.DeviceKind
import dev.kestrel.eval.device.Refusal
import dev.kestrel.eval.report.Report
import dev.kestrel.eval.report.ResultsFile
import dev.kestrel.eval.report.SuiteCall
import dev.kestrel.eval.report.SuiteFolder
import dev.kestrel.eval.run.LineWriter
import dev.kestrel.eval.run.RunOptions
import dev.kestrel.eval.run.RunResult
import dev.kestrel.eval.run.ScriptInstaller
import dev.kestrel.eval.task.Catalogue
import dev.kestrel.eval.task.InjectionFixtures
import dev.kestrel.eval.task.Task
import java.nio.file.Path
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.seconds

/**
 * A series of tasks into a new results folder `eval-results\<device>[-injection]-<yyyyMMdd-HHmmss>\` (design D6 and D10
 * of sub-project 6): `suite.json` with what the series was asked for ([SuiteCall]), `runs.txt` in `Add-SessionResult`'s
 * form, with the line `<task> on <the A71|kestrel-api37>, <n> runs` before each task's run lines, which a71-agent.ps1's
 * `Get-SittingRuns` and `ConvertFrom-TaskLine` read, and `report.json`. `suite` runs the suite of the device (3 runs each
 * by default); `injection` the 14 fixtures on kestrel-api37 (once without a model, 5 times live, by default). With
 * [resume], a suite goes on in the folder of one that stopped ([Resume]): it keeps the tasks whose runs all have a line
 * that is not `reason=stopped`, runs the rest of the last task that was cut short (all of it, and names the kept tasks,
 * when HEAD changed the tool since the folder's runs), then every other task whole after its
 * earlier lines were dropped, and stops when a task's runs name another model than the folder's, whose lines it takes out
 * again; its report is of the whole folder. Returns 0 when no run failed (with [resume], of the whole folder), else 1.
 */
class SeriesCommand(
    private val environment: Environment,
    private val name: String,
    private val live: Boolean,
    private val runs: Int,
    private val resume: String? = null,
    private val recordBaseline: Boolean = false,
    private val pick: (DeviceKind) -> List<Task>,
) {
    /** The report and the folder of the series, once it ended. */
    var report: Report? = null
        private set
    var folder: Path? = null
        private set

    /** The tasks of the folder that still have runs without a line, once a resumed series ended. */
    var incomplete: List<String> = emptyList()
        private set

    fun execute(serial: String, lines: (LineWriter) -> Unit): Int {
        val session = Session(environment, serial)
        val kind = session.device.kind
        val suite = pick(kind)
        val head = commit()
        val resumed = resume?.let { Resume.open(environment, it, kind, live, suite, head, recordBaseline) }
        val tasks = resumed?.let { r -> suite.filter { it.name in r.call.tasks } } ?: suite
        if (tasks.isEmpty()) throw Refusal("The command $name has no task for ${kind.label}.")
        val runs = resumed?.call?.runs ?: runs
        fun count(task: Task) = task.runsAtMost?.let { minOf(it, runs) } ?: runs
        val stamp = environment.clock.now().format(STAMP)
        val folder: Path = resumed?.folder?.path ?: environment.root.resolve("eval-results").resolve("${kind.folder}${if (name == "injection") "-injection" else ""}-$stamp")
        val resumption = resumed?.folder?.resume(tasks.map { it.name to count(it) }, resumed.sameCommit)
        if (resumption != null && resumption.runs.isNotEmpty()) resumed.folder.rewrite(resumption.kept, stamp)
        if (resumed != null && !resumed.sameCommit && resumption!!.kept.isNotEmpty()) {
            session.console.line(
                "${resumption.kept.joinToString { it.task }} ran at ${resumed.commit}, and HEAD $head changed the tool since: judge whether their " +
                    "results still hold before --record-baseline, or remove their blocks from runs.txt and resume again.",
            )
        }
        (resumed?.call ?: SuiteCall(kind.folder, live, runs, tasks.map { it.name }, head)).write(folder)
        val runsFile = ResultsFile(folder.resolve(SuiteFolder.RUNS), environment.clock)
        val writer = LineWriter(session.console, runsFile)
        lines(writer)
        val start = resumed?.folder?.start ?: environment.clock.now()
        val todo = resumption?.runs ?: tasks.map { it.name to 1 }
        val byName = tasks.associateBy { it.name }
        fun finish(failures: Int): Int {
            val all = resumption?.kept.orEmpty().flatMap { block -> block.runs.map { it.line } } + writer.lines
            val report = Report.of(kind, head, live, start, environment.clock.now(), all)
            report.write(folder.resolve("report.json"))
            this.report = report
            this.folder = folder
            if (resumed != null) incomplete = SuiteFolder.read(folder).resume(tasks.map { it.name to count(it) }).runs.map { it.first }
            session.console.line("The results are in $folder.")
            val failed = if (resumed == null) failures > 0 else all.any { it.result == RunResult.FAIL }
            return if (failed) 1 else 0
        }
        // A folder whose tasks all ended needs nothing of the device.
        if (todo.isEmpty()) return finish(0)
        return session.guarded {
            session.prepare(todo.map { byName.getValue(it.first) }, live)
            val runner = session.runner(RunOptions(live), writer)
            var failures = 0
            var model = resumed?.folder?.models?.singleOrNull()
            for ((task, first) in todo.map { byName.getValue(it.first) to it.second }) {
                val mark = SuiteFolder.lines(runsFile.path)
                val before = writer.lines.size
                if (first == 1) runsFile.append(name, "${task.name} on ${kind.label}, ${count(task)} runs")
                failures += runner.runAll(task, if (live) null else ScriptInstaller.resource(task.dryRun!!.script), runs, first)
                if (resumed == null) continue
                val named = writer.lines.drop(before).mapNotNull { it.answer?.get("model") }.filter { it != "none" }.toSet()
                val expected = model ?: named.firstOrNull()
                val other = named.firstOrNull { it != expected }
                if (other != null) {
                    SuiteFolder.truncate(runsFile.path, mark)
                    throw Refusal(
                        "The folder's runs used the model $expected, and this call's runs of ${task.name} used $other; their lines were taken out of " +
                            "runs.txt again. Make $expected the default profile's model again, or run a new suite.",
                    )
                }
                model = expected
            }
            finish(failures)
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
            SeriesCommand(environment, "suite", command.live, command.runs, command.resume, command.recordBaseline) { kind ->
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
