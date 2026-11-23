package dev.kestrel.eval

import dev.kestrel.eval.command.Arguments
import dev.kestrel.eval.command.Command
import dev.kestrel.eval.command.Environment
import dev.kestrel.eval.command.ListCommand
import dev.kestrel.eval.command.RehearseCommand
import dev.kestrel.eval.command.RunCommand
import dev.kestrel.eval.command.SeriesCommand
import dev.kestrel.eval.command.UsageError
import dev.kestrel.eval.config.LocalConfig
import dev.kestrel.eval.config.Redactions
import dev.kestrel.eval.device.Refusal
import dev.kestrel.eval.run.LineWriter
import dev.kestrel.eval.run.Stopping

/**
 * The command line of :tools:eval (design D2 of sub-project 6) and its exit codes: 0 when every run passed or was skipped
 * with a reason, 1 when a run failed or an error stopped the runs, and 2 for a refusal before any run (a device, the live
 * gate, a missing configuration, a command line that it does not take), which starts no run and writes no run line. A
 * command that Ctrl+C ends ([Stopping]) also ends with 2, with no error: the shutdown hook puts the device back and
 * reports what it could not.
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
                is Command.Suite -> SeriesCommand.suite(environment, command).execute(command.device, keep)
                is Command.Injection -> SeriesCommand.injection(environment, command).execute(command.device, keep)
                is Command.Rehearse -> RehearseCommand(environment, command).execute()
                is Command.ListTasks -> ListCommand(environment, command).execute(out)
            }
        } catch (e: Stopping) {
            EXIT_REFUSED
        } catch (e: Refusal) {
            out.println("REFUSED: ${redactions.forConsole(e.message.orEmpty())}")
            if (writer?.lines.isNullOrEmpty()) EXIT_REFUSED else EXIT_FAILED
        } catch (e: Exception) {
            out.println("ERROR: ${redactions.forConsole(e.message ?: e.javaClass.simpleName)}")
            EXIT_FAILED
        }
    }

    companion object {
        const val EXIT_PASSED = 0
        const val EXIT_FAILED = 1
        const val EXIT_REFUSED = 2

        val USAGE: String = """
            |Usage: scripts\eval.ps1 <command> [options]
            |  run --device <serial> --task <name> [--runs n] [--live] [--model id] [--name first-name] [--dry-run] [--results file]
            |  suite --device <serial> [--live] [--runs n] [--tasks a,b]
            |  injection --device <serial> [--live] [--runs n]
            |  rehearse --device <serial> [--tasks a,b]
            |  list [--device <serial>]
        """.trimMargin()
    }
}
