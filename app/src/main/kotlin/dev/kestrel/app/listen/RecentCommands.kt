package dev.kestrel.app.listen

import dev.kestrel.app.commands.CommandReport
import dev.kestrel.commands.Outcome

/**
 * Kestrel's newest instant commands, at most [capacity] of them, in memory only: each one's number, kind and outcome,
 * never its words. The hook voice-state answers from them for a given command number, so that a check of the A71
 * sessions records its own command, also when another utterance started a newer one during its wait.
 */
internal class RecentCommands(private val capacity: Int = 16) {
    /** Command number [command] of the kind [kind]; [outcome] is null while it has not ended. */
    data class Entry(val command: Int, val kind: String, val outcome: Outcome?)

    // In ascending order of the numbers.
    private val entries = ArrayDeque<Entry>()

    /** Notes that command number [command] of the kind [kind] is to run. */
    @Synchronized
    fun started(command: Int, kind: String) {
        if (entries.none { it.command == command }) put(Entry(command, kind, null))
    }

    /** Notes how a command ended. */
    @Synchronized
    fun ended(report: CommandReport) {
        val index = entries.indexOfFirst { it.command == report.command }
        val entry = Entry(report.command, report.kind, report.outcome)
        if (index >= 0) entries[index] = entry else put(entry)
    }

    /** Command number [command], or null when it has not started or is older than the ones kept. */
    @Synchronized
    operator fun get(command: Int): Entry? = entries.firstOrNull { it.command == command }

    private fun put(entry: Entry) {
        val index = entries.indexOfFirst { it.command > entry.command }
        if (index < 0) entries.addLast(entry) else entries.add(index, entry)
        while (entries.size > capacity) entries.removeFirst()
    }
}
