package dev.kestrel.eval

import kotlin.system.exitProcess

/** Starts :tools:eval with the arguments that `scripts\eval.ps1` passes on, and ends with the command's exit code. */
fun main(args: Array<String>) {
    exitProcess(Cli.run(args.toList(), System.out))
}
