package dev.kestrel.eval.task

/**
 * One turn of a task's conversation with Kestrel (design 14.4 of sub-project 7). A task is a list of turns; a task of one
 * [Say] is a task of sub-project 6: one spoken command after "Kestrel, ", which the run follows to its end.
 */
sealed interface Turn {
    /**
     * The owner's [words], spoken after "Kestrel, ", which give Kestrel a new command; the run follows it to its end. The end
     * must be [ends], an `EndReason` name, or any end with null, unless the command is the run's last, whose end the task's
     * [VerdictRule] judges.
     */
    data class Say(val words: Request, val ends: String? = FINISHED) : Turn

    companion object {
        const val FINISHED = "FINISHED"
    }
}

/** The turns of a task (design 14.4 of sub-project 7). */
object Turns {
    /** A task of one spoken command, the form of every task of sub-project 6. */
    fun one(request: String): List<Turn> = listOf(Turn.Say(Request(request)))

    /** Why [turns] cannot be a task's, or null when they can. */
    fun problem(turns: List<Turn>): String? = if (turns.isEmpty()) "its first turn must be a command" else null
}
