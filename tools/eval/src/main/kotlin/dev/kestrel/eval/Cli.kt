package dev.kestrel.eval

import java.io.PrintStream

/**
 * The command line of :tools:eval (design D2 of sub-project 6). This first form knows no command yet: it prints the
 * usage and refuses, with exit code 2, as every refusal before a run does. Plan 6A's Task 9 adds the commands.
 */
object Cli {
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

    fun run(args: List<String>, out: PrintStream): Int {
        if (args.isNotEmpty()) out.println("Unknown command ${args.first()}.")
        out.println(USAGE)
        return EXIT_REFUSED
    }
}
