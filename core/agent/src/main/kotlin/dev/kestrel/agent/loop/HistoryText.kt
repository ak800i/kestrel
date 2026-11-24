package dev.kestrel.agent.loop

import dev.kestrel.agent.goal.HistoryMatch
import dev.kestrel.agent.goal.HistoryPort
import dev.kestrel.agent.goal.HistoryResult
import dev.kestrel.agent.observe.ScreenText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The result of `search_conversations` as the model reads it (design 9.4 of sub-project 7): at most
 * [HistoryPort.MAX_MATCHES] earlier commands, newest first, in at most [TOKEN_BUDGET] tokens. Each match names its day,
 * its time and its command's number; what the owner said then and what Kestrel answered, in quotes; the apps that it acted
 * in; and its data notes as the model saw them then, so that everything in it stays data (design 12.1). Every text was
 * redacted once, when it entered its run, so nothing is redacted here. A data note that would pass the budget is left out
 * with a line that says so, and a match that would pass it is left out whole and counted.
 */
internal object HistoryText {
    const val TOKEN_BUDGET = 1_500
    const val NO_MATCH = "No match."
    const val NO_OTHER = "No other match."
    const val MORE = "More earlier commands match; search with other words or a date range."

    /** The result's [text] and how many matches it shows ([count]). */
    class Shown(val text: Redacted, val count: Int) {
        override fun toString(): String = "Shown(text=$text, count=$count)"
    }

    private val FORMAT = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm", Locale.ENGLISH)

    fun of(result: HistoryResult, zone: ZoneId): Shown {
        if (result.matches.isEmpty()) return Shown(Redacted.kestrel(NO_MATCH), 0)
        val offered = result.matches.take(HistoryPort.MAX_MATCHES)
        val more = result.more || result.matches.size > offered.size
        // Room for the end line, whichever it becomes.
        val reserve = maxOf(ScreenText.tokens(MORE), ScreenText.tokens(leftOut(offered.size)))
        val blocks = mutableListOf<String>()
        var used = 0
        for ((index, match) in offered.withIndex()) {
            var block = head(index + 1, match, zone)
            if (used + ScreenText.tokens(block) + reserve > TOKEN_BUDGET) break
            for (note in match.data) {
                val full = "  Data: ${note.tool}: " + note.text.replace("\n", "\n    ")
                val short = "  Data: ${note.tool}: left out, because the result would be too long."
                val line = listOf(full, short).firstOrNull { used + ScreenText.tokens(block + "\n" + it) + reserve <= TOKEN_BUDGET } ?: continue
                block += "\n" + line
            }
            blocks += block
            used += ScreenText.tokens(block + "\n")
        }
        val left = offered.size - blocks.size
        val end = when {
            left > 0 -> leftOut(left)
            more -> MORE
            else -> NO_OTHER
        }
        return Shown(Redacted.kestrel((blocks + end).joinToString("\n")), blocks.size)
    }

    private fun head(number: Int, match: HistoryMatch, zone: ZoneId): String {
        val time = FORMAT.format(Instant.ofEpochMilli(match.atMillis).atZone(zone))
        val lines = mutableListOf("Match $number · $time · command ${match.command} of that conversation")
        lines += "  The owner said: " + ScreenText.quote(match.request, ScreenText.VALUE_LIMIT)
        match.answer?.let { lines += "  Kestrel answered: " + ScreenText.quote(it, ScreenText.VALUE_LIMIT) }
        if (match.apps.isNotEmpty()) lines += "  Apps: " + match.apps.joinToString(", ") { ScreenText.escape(ScreenText.cut(it, ScreenText.LABEL_LIMIT)) }
        return lines.joinToString("\n")
    }

    private fun leftOut(count: Int): String =
        "$count more ${if (count == 1) "match is" else "matches are"} left out, because the result would be too long; search with other words or a date range."
}
