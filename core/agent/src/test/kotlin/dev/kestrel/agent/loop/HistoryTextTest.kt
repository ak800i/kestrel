package dev.kestrel.agent.loop

import dev.kestrel.agent.goal.HistoryData
import dev.kestrel.agent.goal.HistoryMatch
import dev.kestrel.agent.goal.HistoryResult
import dev.kestrel.agent.observe.ScreenText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Design 9.4 of sub-project 7: search_conversations' result, as data, within its budget. */
class HistoryTextTest {
    private val zone = ZoneId.of("Europe/Belgrade")
    private val thursday = ZonedDateTime.of(2026, 11, 19, 18, 4, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun aMatchNamesItsTimeItsCommandTheWordsTheAnswerTheAppsAndTheData() {
        val match = HistoryMatch(
            thursday, 2, "Find the restaurant Mila sent me last week", "It's Ambar, on Karađorđeva 2.", listOf("Viber"),
            listOf(HistoryData("list_sms", "from: \"Mila\" · body: \"Ambar\"")),
        )

        val shown = HistoryText.of(HistoryResult(listOf(match)), zone)

        assertEquals(1, shown.count)
        assertEquals(
            """
            Match 1 · Thursday, 19 November 2026, 18:04 · command 2 of that conversation
              The owner said: "Find the restaurant Mila sent me last week"
              Kestrel answered: "It's Ambar, on Karađorđeva 2."
              Apps: Viber
              Data: list_sms: from: "Mila" · body: "Ambar"
            No other match.
            """.trimIndent(),
            shown.text.text,
        )
    }

    // Design 9.4 of sub-project 7: the port gives the best match first, the newer on a tie, and the result keeps its order.
    @Test
    fun theMatchesKeepThePortsOrderBestMatchFirst() {
        val older = HistoryMatch(thursday - 86_400_000L, 1, "Find the restaurant Mila sent me", null, emptyList(), emptyList())
        val newer = HistoryMatch(thursday, 2, "Call Mila", null, emptyList(), emptyList())

        val lines = HistoryText.of(HistoryResult(listOf(older, newer)), zone).text.text.lines()

        assertTrue(lines[0], lines[0].startsWith("Match 1 · Wednesday, 18 November 2026, 18:04 · command 1 "))
        assertTrue(lines[2], lines[2].startsWith("Match 2 · Thursday, 19 November 2026, 18:04 · command 2 "))
    }

    @Test
    fun noMatchAndMoreMatchesThanItShowsSaySo() {
        assertEquals(HistoryText.NO_MATCH, HistoryText.of(HistoryResult(emptyList()), zone).text.text)
        val six = List(6) { HistoryMatch(thursday - it * 60_000L, it + 1, "Read my SMS", null, emptyList(), emptyList()) }

        val shown = HistoryText.of(HistoryResult(six), zone)

        assertEquals(5, shown.count)
        assertTrue(shown.text.text.endsWith("\n" + HistoryText.MORE))
        assertTrue(HistoryText.of(HistoryResult(six.take(2), more = true), zone).text.text.endsWith("\n" + HistoryText.MORE))
    }

    @Test
    fun theResultStaysWithinItsBudget() {
        val long = HistoryData("list_sms", "body: \"" + "x".repeat(3_000) + "\"")
        val matches = List(5) { HistoryMatch(thursday - it * 60_000L, it + 1, "Read my SMS", null, emptyList(), listOf(long)) }

        val shown = HistoryText.of(HistoryResult(matches), zone)

        assertEquals(5, shown.count)
        assertTrue(ScreenText.tokens(shown.text.text) <= HistoryText.TOKEN_BUDGET)
        assertTrue(shown.text.text.contains("  Data: list_sms: left out, because the result would be too long."))
    }

    @Test
    fun dataNotesThatFitNotEvenAsTheirShortLineAreCountedAtTheEnd() {
        val tooLong = HistoryData("list_sms", "body: \"" + "x".repeat(6_000) + "\"")
        val match = HistoryMatch(thursday, 1, "Read my SMS", null, emptyList(), List(100) { tooLong })

        val shown = HistoryText.of(HistoryResult(listOf(match)), zone)

        val text = shown.text.text
        val short = text.lines().count { it == "  Data: list_sms: left out, because the result would be too long." }
        assertEquals(1, shown.count)
        assertTrue(short in 1 until 100)
        assertTrue(text.endsWith("\n${100 - short} data notes are left out, because the result would be too long; search with other words or a date range."))
        assertTrue(ScreenText.tokens(text) <= HistoryText.TOKEN_BUDGET)
    }
}
