package dev.kestrel.eval.command

import dev.kestrel.eval.report.Comparison

/** A command line that the tool does not take; it is refused with the usage, exit code 2. */
class UsageError(message: String) : Exception(message)

/** The commands of :tools:eval (design D2 of sub-project 6). */
sealed interface Command {
    data class Run(
        val device: String,
        val task: String,
        val runs: Int = 3,
        val live: Boolean = false,
        val model: String? = null,
        val name: String? = null,
        val dryRun: Boolean = false,
        val results: String? = null,
    ) : Command

    data class Suite(
        val device: String,
        val live: Boolean = false,
        val runs: Int = 3,
        val tasks: List<String>? = null,
        val compare: Boolean = false,
        val recordBaseline: Boolean = false,
        /** The results folder of a suite that stopped, which this call continues. */
        val resume: String? = null,
    ) : Command

    /** [device] is the serial for `--rerun`'s live runs, which the folders cannot name for the emulator. */
    data class Compare(val folders: List<String>, val rerun: Boolean = false, val device: String? = null) : Command

    /** [runs] is 1 without a model and 5 live when the command line gives none. */
    data class Injection(val device: String, val live: Boolean = false, val runs: Int? = null) : Command

    data class Rehearse(val device: String, val tasks: List<String>? = null) : Command

    data class EgressSetup(val device: String) : Command

    data class ListTasks(val device: String? = null) : Command
}

/** Reads the command line: a command and its options, each option once, `--runs` from 1 to 10. */
object Arguments {
    private val FLAGS = setOf("--live", "--dry-run", "--compare", "--record-baseline", "--rerun")
    private val VALUES = setOf("--device", "--task", "--runs", "--model", "--name", "--results", "--tasks", "--resume")
    private val ALLOWED = mapOf(
        "run" to setOf("--device", "--task", "--runs", "--live", "--model", "--name", "--dry-run", "--results"),
        "suite" to setOf("--device", "--live", "--runs", "--tasks", "--compare", "--record-baseline", "--resume"),
        "compare" to setOf("--rerun", "--device"),
        "injection" to setOf("--device", "--live", "--runs"),
        "rehearse" to setOf("--device", "--tasks"),
        "egress-setup" to setOf("--device"),
        "list" to setOf("--device"),
    )

    fun parse(args: List<String>): Command {
        val name = args.firstOrNull() ?: throw UsageError("Give a command.")
        val allowed = ALLOWED[name] ?: throw UsageError("Unknown command $name.")
        val options = mutableMapOf<String, String>()
        val folders = mutableListOf<String>()
        var at = 1
        if (name == "compare") while (at < args.size && !args[at].startsWith("--")) folders.add(args[at++])
        while (at < args.size) {
            val option = args[at++]
            if (option !in allowed) throw UsageError("$name takes no option $option.")
            if (option in options) throw UsageError("$option is given twice.")
            options[option] = when (option) {
                in FLAGS -> "true"
                in VALUES -> args.getOrNull(at++)?.takeIf { !it.startsWith("--") } ?: throw UsageError("$option needs a value.")
                else -> throw UsageError("$name takes no option $option.")
            }
        }
        fun device() = options["--device"] ?: throw UsageError("$name needs --device <serial>.")
        val live = options["--live"] == "true"
        val runs = options["--runs"]?.let { value -> value.toIntOrNull()?.takeIf { it in 1..10 } ?: throw UsageError("--runs takes 1 to 10, not $value.") }
        val tasks = options["--tasks"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        return when (name) {
            "run" -> {
                if (live && options["--dry-run"] == "true") throw UsageError("--live calls the owner's provider, and --dry-run runs the scripted model; give one of them.")
                Command.Run(
                    device(), options["--task"] ?: throw UsageError("run needs --task <name>."), runs ?: 3, live, options["--model"], options["--name"],
                    options["--dry-run"] == "true", options["--results"],
                )
            }
            "suite" -> {
                val record = options["--record-baseline"] == "true"
                val compare = options["--compare"] == "true"
                if (record && (!live || tasks != null)) throw UsageError("--record-baseline records a live suite of every task: give --live and no --tasks.")
                if (compare && !live) throw UsageError("--compare judges a live suite: give --live.")
                if ((record || compare) && runs != null && runs != Comparison.RUNS) {
                    throw UsageError("--record-baseline and --compare take design D10's ${Comparison.RUNS} runs per task, not $runs: leave out --runs.")
                }
                val resume = options["--resume"]
                if (resume != null && (tasks != null || runs != null)) throw UsageError("--resume runs the tasks and the runs of its folder's suite: give no --tasks and no --runs.")
                Command.Suite(device(), live, runs ?: 3, tasks, compare, record, resume)
            }
            "compare" -> {
                if (folders.isEmpty()) throw UsageError("compare needs one or more results folders.")
                val rerun = options["--rerun"] == "true"
                if (rerun && options["--device"] == null) throw UsageError("compare --rerun needs --device <serial> of the device that the folder names.")
                Command.Compare(folders, rerun, options["--device"])
            }
            "injection" -> Command.Injection(device(), live, runs)
            "rehearse" -> Command.Rehearse(device(), tasks)
            "egress-setup" -> Command.EgressSetup(device())
            else -> Command.ListTasks(options["--device"])
        }
    }
}
