package dev.kestrel.eval

import dev.kestrel.eval.command.Environment
import kotlin.system.exitProcess

/** Starts :tools:eval with the arguments that `scripts\eval.ps1` passes on, and ends with the command's exit code. */
fun main(args: Array<String>) {
    exitProcess(Cli(Environment.local(System.out)).run(args.toList()))
}
