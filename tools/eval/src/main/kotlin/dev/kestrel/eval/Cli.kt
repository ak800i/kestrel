package dev.kestrel.eval

import dev.kestrel.eval.command.Arguments
import dev.kestrel.eval.command.Command
import dev.kestrel.eval.command.CompareCommand
import dev.kestrel.eval.command.EgressSetupCommand
import dev.kestrel.eval.command.Environment
import dev.kestrel.eval.command.ListCommand
import dev.kestrel.eval.command.RehearseCommand
import dev.kestrel.eval.command.RunCommand
import dev.kestrel.eval.command.SeriesCommand
import dev.kestrel.eval.command.UsageError
import dev.kestrel.eval.config.LocalConfig
import dev.kestrel.eval.config.Redactions
import dev.kestrel.eval.device.Refusal
import dev.kestrel.eval.report.Baseline
import dev.kestrel.eval.run.LineWriter
import dev.kestrel.eval.run.OwnerBusy
import dev.kestrel.eval.run.Stopping

/**
 * The command line of :tools:eval (design D2 of sub-project 6) and its exit codes: 0 when every run passed or was skipped
 * with a reason, 1 when a run failed or an error stopped the runs, and 2 for a refusal before any run (a device, the live
 * gate, a missing configuration, a command line that it does not take), which starts no run and writes no run line. A
 * command that Ctrl+C ends ([Stopping]) prints no error: it ends with 1 when run lines were written (the shutdown hook
 * writes `reason=stopped` for the runs that did not end), and with 2 when none was. On a real Ctrl+C the JVM is already
 * shutting down, so the process ends with the JVM's own exit code instead. A command that the owner's open conversation
 * stops ([OwnerBusy]) prints `STOPPED:` with the reason `owner-busy` and ends with 3, a code of its own, so that a caller
 * that runs the tool once per task, as scripts\a71-agent.ps1 does, stops too; the runs that it did not run have
 * `result=skipped reason=owner-busy`, which a resumed suite runs. An error that came after the first one, such
 * as a change of kestrel-api37's accounts after a failed command, is printed after it, and makes a refusal an error (1).
 */
class Cli(private val environment: Environment) {
    fun run(args: List<String>): Int {
        val out = environment.out
        val command = try {
            Arguments.parse(args)
        } catch (e: UsageError) {
            out.println(e.message)
            out.println(USAGE)
            return EXIT_REFUSED
        }
        // Read once, before any command: a malformed file makes every read throw, so an error's catch cannot read it again.
        val redactions = try {
            Redactions(LocalConfig.read(environment.root.resolve("corpus").resolve("owner").resolve("a71-config.json")))
        } catch (e: Refusal) {
            out.println("REFUSED: ${Redactions(LocalConfig.EMPTY).forConsole(e.message.orEmpty())}")
            return EXIT_REFUSED
        }
        var writer: LineWriter? = null
        val keep: (LineWriter) -> Unit = { writer = it }
        return try {
            when (command) {
                is Command.Run -> RunCommand(environment, command).execute(keep)
                is Command.Suite -> suite(command, keep)
                is Command.Compare -> CompareCommand(environment, command).execute()
                is Command.Injection -> SeriesCommand.injection(environment, command).execute(command.device, keep)
                is Command.Rehearse -> RehearseCommand(environment, command).execute()
                is Command.EgressSetup -> EgressSetupCommand(environment, command).execute()
                is Command.ListTasks -> ListCommand(environment, command).execute(out)
            }
        } catch (e: Stopping) {
            errors(e, redactions)
            if (writer?.lines.isNullOrEmpty()) EXIT_REFUSED else EXIT_FAILED
        } catch (e: OwnerBusy) {
            out.println("STOPPED: ${redactions.forConsole(e.message.orEmpty())}")
            errors(e, redactions)
            EXIT_OWNER_BUSY
        } catch (e: Refusal) {
            out.println("REFUSED: ${redactions.forConsole(e.message.orEmpty())}")
            errors(e, redactions)
            if (writer?.lines.isNullOrEmpty() && e.suppressed.isEmpty()) EXIT_REFUSED else EXIT_FAILED
        } catch (e: Exception) {
            out.println("ERROR: ${redactions.forConsole(e.message ?: e.javaClass.simpleName)}")
            errors(e, redactions)
            EXIT_FAILED
        }
    }

    /**
     * A suite, then with `--record-baseline` its device's part of `eval\baseline.json` (design D10 of sub-project 6), and
     * with `--compare` the judgement of its folder, with the re-runs on the same device. With `--resume`, the part is of
     * the whole folder, and only once each of its tasks' runs has a line.
     */
    private fun suite(command: Command.Suite, keep: (LineWriter) -> Unit): Int {
        val series = SeriesCommand.suite(environment, command)
        var code = series.execute(command.device, keep)
        val report = series.report ?: return code
        if (command.recordBaseline) {
            val file = environment.root.resolve("eval").resolve("baseline.json")
            if (series.incomplete.isNotEmpty()) {
                throw Refusal("eval\\baseline.json was not changed: ${series.incomplete.joinToString()} still have runs without a line in ${series.folder}; resume it again.")
            }
            Baseline.problem(report)?.let { throw Refusal("eval\\baseline.json was not changed: $it. The suite's results are still in ${series.folder}.") }
            Baseline.read(file).with(report.device, Baseline.part(report)).write(file)
            environment.out.println("eval\\baseline.json now holds the part of ${report.device}, commit ${report.commit}, model ${report.model}.")
            report.egress?.takeIf { !it.passed }?.let {
                environment.out.println("The egress check of the recorded runs failed (connections=${it.connections} failures=${it.failures} unknown=${it.unknown}); see egress.txt in ${series.folder}.")
            }
        }
        val folder = series.folder
        if (command.compare && folder != null) {
            code = maxOf(code, CompareCommand(environment, Command.Compare(listOf(folder.toString()), rerun = true, device = command.device)).execute())
        }
        return code
    }

    /** The errors that came after [first], such as a change of kestrel-api37's accounts after a command that failed. */
    private fun errors(first: Throwable, redactions: Redactions) {
        for (later in first.suppressed) environment.out.println("ERROR: ${redactions.forConsole(later.message ?: later.javaClass.simpleName)}")
    }

    companion object {
        const val EXIT_PASSED = 0
        const val EXIT_FAILED = 1
        const val EXIT_REFUSED = 2
        const val EXIT_OWNER_BUSY = 3

        val USAGE: String = """
            |Usage: scripts\eval.ps1 <command> [options]
            |  run --device <serial> --task <name> [--runs n] [--live] [--model id] [--name first-name] [--dry-run] [--results file]
            |  suite --device <serial> [--live] [--runs n] [--tasks a,b] [--compare] [--record-baseline] [--egress] [--resume <results folder>]
            |  compare <results folder>... [--rerun --device <serial>]
            |  injection --device <serial> [--live] [--runs n]
            |  rehearse --device <serial> [--tasks a,b]
            |  egress-setup --device <serial>
            |  list [--device <serial>]
        """.trimMargin()
    }
}
