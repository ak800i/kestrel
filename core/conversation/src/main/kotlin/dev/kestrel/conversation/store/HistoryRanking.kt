package dev.kestrel.conversation.store

import dev.kestrel.agent.goal.HistoryQuery
import dev.kestrel.commands.text.LabelMatcher
import dev.kestrel.commands.text.MatchRules
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.conversation.model.ConversationId
import java.time.Instant
import java.time.ZoneId

/** A record of a conversation: its id and the record's number in it. */
data class RecordRef(val conversation: ConversationId, val record: Int)

/** A record that matched, with how many of the query's words it holds. */
data class RankedEntry(val entry: IndexedRecord, val score: Int)

/**
 * The search's matching and order (design 9.4 of sub-project 7), a pure function of the indexes and the query, apart from
 * where the indexes come from and from the details of a match, which its record gives. The query is folded with
 * `Normalizer.words`, as the indexes' records were, so letter case, diacritics and the two Serbian scripts fold as the
 * matcher folds them; a fixed list of words that hold no topic ([IGNORED]) is not counted. A query's word counts when the
 * record holds it, or a near miss of it: a word of at least [NEAR_LETTERS] letters with the same first letter that
 * `LabelMatcher` scores at `MatchRules.ACCEPT` or more. A record must hold at least half of the query's words; more words
 * rank higher, and ties go to the newer record.
 */
object HistoryRanking {
    const val NEAR_LETTERS = 4

    /** Words that hold no topic, in English and Serbian. */
    val IGNORED: Set<String> = setOf(
        "the", "a", "an", "about", "of", "to", "and", "or", "in", "on", "at", "for", "with", "from", "my", "our", "me",
        "it", "that", "this", "i", "u", "na", "o", "sa", "za", "od", "do", "je", "da", "se", "mi", "moj", "moja",
    )

    /** The query's words that count, folded, each once; empty when [text] holds no topic. */
    fun wanted(text: String): List<String> = Normalizer.words(text).filter { it !in IGNORED }.distinct()

    /**
     * The records of [indexes] that [reached] lets through and that match [query], best match first and the newer first on a
     * tie: only those given on a day from [HistoryQuery.from] to [HistoryQuery.to] in [zone], both included. Empty when the
     * query holds no word that counts. In each index, each query word's matching words (itself and its near misses, among
     * the index's words with its first letter) are found once, and a record then only compares positions. A near miss is
     * judged by `LabelMatcher.mayAccept` first, which is exact and cheap, and by [similarity], the matcher's score
     * (`LabelMatcher.score`; a test may count its calls), only for the few words that pass it. Each distinct word is judged
     * at most once per query word, however many records and indexes hold it.
     */
    fun rank(
        indexes: List<SearchIndex>,
        query: HistoryQuery,
        zone: ZoneId,
        reached: (IndexedRecord) -> Boolean = { true },
        similarity: (String, String) -> Double = { wanted, word -> LabelMatcher.score(wanted, word) },
    ): List<RankedEntry> {
        val wanted = wanted(query.text)
        if (wanted.isEmpty()) return emptyList()
        val judged = wanted.map { HashMap<String, Boolean>() }
        return indexes.flatMap { index ->
            val counted = wanted.mapIndexed { n, word -> matching(index, word, judged[n], similarity) }
            index.records.filter { reached(it) && within(it, query, zone) }.mapNotNull { record -> score(record, counted)?.let { RankedEntry(record, it) } }
        }.sortedWith(compareByDescending<RankedEntry> { it.score }.thenByDescending { it.entry.atWall })
    }

    // The positions in [index] of [wanted] and of its near misses, in order; [judged] keeps each word's verdict for the
    // other indexes.
    private fun matching(index: SearchIndex, wanted: String, judged: MutableMap<String, Boolean>, similarity: (String, String) -> Double): IntArray {
        val exact = index.position(wanted)
        val letter = if (wanted.length >= NEAR_LETTERS) index.startingWith(wanted[0]) else IntRange.EMPTY
        val near = letter.filter { position ->
            val word = index.words[position]
            position != exact && word.length >= NEAR_LETTERS && judged.getOrPut(word) {
                LabelMatcher.mayAccept(wanted, word) && similarity(wanted, word) + MatchRules.TOLERANCE >= MatchRules.ACCEPT
            }
        }
        return (near + listOfNotNull(exact.takeIf { it >= 0 })).sorted().toIntArray()
    }

    private fun within(record: IndexedRecord, query: HistoryQuery, zone: ZoneId): Boolean {
        if (query.from == null && query.to == null) return true
        val day = Instant.ofEpochMilli(record.atWall).atZone(zone).toLocalDate()
        return (query.from == null || day >= query.from) && (query.to == null || day <= query.to)
    }

    /** How many query words [record] holds, each given by the positions that count as it ([counted]); null under half. */
    private fun score(record: IndexedRecord, counted: List<IntArray>): Int? {
        val score = counted.count { positions -> positions.any { record.words.binarySearch(it) >= 0 } }
        return score.takeIf { it * 2 >= counted.size }
    }
}
