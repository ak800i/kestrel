package dev.kestrel.eval.command

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

    data class Suite(val device: String, val live: Boolean = false, val runs: Int = 3, val tasks: List<String>? = null) : Command

    /** [runs] is 1 without a model and 5 live when the command line gives none. */
    data class Injection(val device: String, val live: Boolean = false, val runs: Int? = null) : Command

    data class Rehearse(val device: String, val tasks: List<String>? = null) : Command

    data class ListTasks(val device: String? = null) : Command
}

/** Reads the command line: a command and its options, each option once, `--runs` from 1 to 10. */
object Arguments {
    private val FLAGS = setOf("--live", "--dry-run")
    private val VALUES = setOf("--device", "--task", "--runs", "--model", "--name", "--results", "--tasks")
    private val ALLOWED = mapOf(
        "run" to setOf("--device", "--task", "--runs", "--live", "--model", "--name", "--dry-run", "--results"),
        "suite" to setOf("--device", "--live", "--runs", "--tasks"),
        "injection" to setOf("--device", "--live", "--runs"),
        "rehearse" to setOf("--device", "--tasks"),
        "list" to setOf("--device"),
    )

    fun parse(args: List<String>): Command {
        val name = args.firstOrNull() ?: throw UsageError("Give a command.")
        val allowed = ALLOWED[name] ?: throw UsageError("Unknown command $name.")
        val options = mutableMapOf<String, String>()
        var at = 1
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
            "suite" -> Command.Suite(device(), live, runs ?: 3, tasks)
            "injection" -> Command.Injection(device(), live, runs)
            "rehearse" -> Command.Rehearse(device(), tasks)
            else -> Command.ListTasks(options["--device"])
        }
    }
}
