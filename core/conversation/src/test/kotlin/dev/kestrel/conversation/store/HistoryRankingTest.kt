package dev.kestrel.conversation.store

import dev.kestrel.agent.goal.HistoryQuery
import dev.kestrel.commands.text.LabelMatcher
import dev.kestrel.commands.text.MatchRules
import dev.kestrel.conversation.model.CommandRecord
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.ConversationSnapshot
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.testing.ManualConversationClock
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class HistoryRankingTest {
    private val zone = ManualConversationClock.BELGRADE

    private fun conversation(n: Int, at: Long, words: List<String>) = ConversationSnapshot(
        ConversationId(n.toString().padStart(32, '0')), at, at, testMode = true,
        records = listOf(CommandRecord(1, at, InputChannel.TEXT, words.joinToString(" "))),
    )

    /** The rule as design 9.4 states it, scoring every word of every record, which [HistoryRanking.rank] must agree with. */
    private fun everyRecord(index: SearchIndex, text: String): List<Pair<RecordRef, Int>> {
        val wanted = HistoryRanking.wanted(text)
        fun near(word: String, other: String) = word.length >= HistoryRanking.NEAR_LETTERS && other.length >= HistoryRanking.NEAR_LETTERS &&
            word[0] == other[0] && LabelMatcher.score(word, other) + MatchRules.TOLERANCE >= MatchRules.ACCEPT
        return index.records.mapNotNull { record ->
            val words = index.wordsOf(record)
            val score = wanted.count { word -> word in words || words.any { near(word, it) } }
            if (wanted.isNotEmpty() && score * 2 >= wanted.size) record to score else null
        }.sortedWith(compareByDescending<Pair<IndexedRecord, Int>> { it.second }.thenByDescending { it.first.atWall }).map { it.first.ref to it.second }
    }

    @Test
    fun theNearMissesWorkedOutOncePerIndexRankAsScoringEveryRecordDoes() {
        val words = listOf("milica", "milicu", "milan", "mila", "marko", "markom", "restoran", "restorana", "ambar", "ambaru", "poslala", "novi", "sad", "vino")
        val random = Random(7)
        val index = SearchIndex.of((1..60).map { n -> conversation(n, 1_000L * random.nextInt(20), List(random.nextInt(1, 5)) { words.random(random) }) })
        val queries = listOf("Milica", "Marko restoran", "milan ambar novi", "restoran vino Mila", "Ambar", "sad", "the about", "Milicu poslala restorana")

        for (query in queries) {
            assertEquals(query, everyRecord(index, query), HistoryRanking.rank(listOf(index), HistoryQuery(query), zone).map { it.entry.ref to it.score })
        }
    }

    // Design R3: the A71 would take most of a second to score every word with the query's first letter, so the matcher's
    // cheap check comes first, and a word that two indexes (two months) share is judged once.
    @Test
    fun eachWordIsScoredOnceAndOnlyWhenTheCheapCheckLetsItThrough() {
        val first = SearchIndex.of((1..200).map { conversation(it, it.toLong(), listOf("milicu", "mileva", "restoran", "x$it")) })
        val second = SearchIndex.of((201..300).map { conversation(it, it.toLong(), listOf("milicu", "mileva", "y$it")) })
        val scored = mutableListOf<String>()
        val counting = { wanted: String, word: String -> scored += word; LabelMatcher.score(wanted, word) }

        val ranked = HistoryRanking.rank(listOf(first, second), HistoryQuery("Milica"), zone, similarity = counting)

        assertEquals(300, ranked.size)
        assertEquals(listOf("milicu"), scored)
    }
}
