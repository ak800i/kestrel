package dev.kestrel.eval.command

import dev.kestrel.eval.device.Refusal
import dev.kestrel.eval.report.ResultsFile
import dev.kestrel.eval.run.CheckResult
import dev.kestrel.eval.run.LineWriter
import dev.kestrel.eval.run.Reason
import dev.kestrel.eval.run.RunLine
import dev.kestrel.eval.run.RunOptions
import dev.kestrel.eval.run.RunResult
import dev.kestrel.eval.run.ScriptInstaller
import dev.kestrel.eval.task.Catalogue
import java.nio.file.Paths

/**
 * `run --device <s> --task <name>` (design D2 of sub-project 6): one task, as task-check.ps1 ran it, with its lines on the
 * console and appended to `--results` with the step `task`. Without `--live` the runs use the task's form without a
 * model: its stand-in with its script, as scripts\a71-agent.ps1's dry run needs, and `--dry-run` runs that form once; a
 * task without one writes `result=skipped reason=untestable`. Returns 0 when no run failed, else 1.
 */
class RunCommand(private val environment: Environment, private val command: Command.Run) {
    fun execute(lines: (LineWriter) -> Unit): Int {
        val task = Catalogue.find(command.task) ?: throw Refusal("Unknown task ${command.task}; scripts\\eval.ps1 list shows the tasks.")
        val session = Session(environment, command.device)
        val writer = LineWriter(session.console, command.results?.let { ResultsFile(Paths.get(it), environment.clock) })
        lines(writer)
        val dryRun = task.dryRun
        if (!command.live && dryRun == null) {
            writer.write(RunLine(task.name, 1, RunResult.SKIPPED, null, CheckResult.SKIPPED, Reason.UNTESTABLE))
            session.console.line("Task ${task.name} has no form without a model, so it runs only with --live.")
            return 0
        }
        val executed = if (command.live) task else dryRun!!.standIn?.let { Catalogue.find(it) } ?: task
        val script = if (command.live) null else ScriptInstaller.resource(dryRun!!.script)
        val runs = if (command.dryRun) 1 else command.runs
        return session.guarded {
            session.prepare(listOf(executed), command.live)
            val failures = session.runner(RunOptions(command.live, command.model, command.name), writer).runAll(executed, script, runs)
            if (failures > 0) 1 else 0
        }
    }
}
