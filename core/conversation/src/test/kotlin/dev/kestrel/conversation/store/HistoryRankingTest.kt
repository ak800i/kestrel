package dev.kestrel.conversation.store

import dev.kestrel.agent.goal.HistoryQuery
import dev.kestrel.commands.text.LabelMatcher
import dev.kestrel.commands.text.MatchRules
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.testing.ManualConversationClock
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class HistoryRankingTest {
    private val zone = ManualConversationClock.BELGRADE

    private fun ref(n: Int) = RecordRef(ConversationId(n.toString().padStart(32, '0')), 1)

    /** The rule as design 9.4 states it, scoring every word of every entry, which [HistoryRanking.rank] must agree with. */
    private fun everyRecord(entries: List<SearchEntry>, text: String): List<RankedEntry> {
        val wanted = HistoryRanking.wanted(text)
        fun near(word: String, other: String) = word.length >= HistoryRanking.NEAR_LETTERS && other.length >= HistoryRanking.NEAR_LETTERS &&
            word[0] == other[0] && LabelMatcher.score(word, other) + MatchRules.TOLERANCE >= MatchRules.ACCEPT
        return entries.mapNotNull { entry ->
            val score = wanted.count { word -> word in entry.words || entry.words.any { near(word, it) } }
            if (wanted.isNotEmpty() && score * 2 >= wanted.size) RankedEntry(entry, score) else null
        }.sortedWith(compareByDescending<RankedEntry> { it.score }.thenByDescending { it.entry.atWall })
    }

    @Test
    fun theNearMissesWorkedOutOnceRankAsScoringEveryRecordDoes() {
        val words = listOf("milica", "milicu", "milan", "mila", "marko", "markom", "restoran", "restorana", "ambar", "ambaru", "poslala", "novi", "sad", "vino")
        val random = Random(7)
        val entries = (1..60).map { n -> SearchEntry(ref(n), 1_000L * random.nextInt(20), List(random.nextInt(1, 5)) { words.random(random) }.toSet()) }
        val queries = listOf("Milica", "Marko restoran", "milan ambar novi", "restoran vino Mila", "Ambar", "sad", "the about", "Milicu poslala restorana")

        for (query in queries) assertEquals(query, everyRecord(entries, query), HistoryRanking.rank(entries, HistoryQuery(query), zone))
    }

    @Test
    fun eachDistinctWordIsScoredOnceHoweverManyEntriesHoldIt() {
        val entries = (1..200).map { SearchEntry(ref(it), it.toLong(), setOf("milicu", "mileva", "restoran", "x$it")) }
        var calls = 0
        val counting = { wanted: String, word: String -> calls++; LabelMatcher.score(wanted, word) }

        val ranked = HistoryRanking.rank(entries, HistoryQuery("Milica"), zone, counting)

        assertEquals(200, ranked.size)
        assertEquals(2, calls)
    }
}
