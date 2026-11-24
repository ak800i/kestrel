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
}
