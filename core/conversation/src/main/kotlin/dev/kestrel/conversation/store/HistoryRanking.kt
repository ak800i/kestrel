package dev.kestrel.conversation.store

import dev.kestrel.agent.goal.HistoryQuery
import dev.kestrel.commands.text.LabelMatcher
import dev.kestrel.commands.text.MatchRules
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.conversation.model.CommandRecord
import dev.kestrel.conversation.model.ConversationId
import java.time.Instant
import java.time.ZoneId

/** A record of a conversation: its id and the record's number in it. */
data class RecordRef(val conversation: ConversationId, val record: Int)

/**
 * One command as the search sees it (design 9.4 of sub-project 7): where it is, when it was given (wall clock) and the
 * folded words of its texts. The folded words are the owner's words and Kestrel's, folded, so they are kept in memory only;
 * toString prints only counts.
 */
data class SearchEntry(val ref: RecordRef, val atWall: Long, val words: Set<String>) {
    override fun toString(): String = "SearchEntry(record=${ref.record}, words=${words.size})"

    companion object {
        /** The entry of [record] of the conversation [conversation]: the words of every text that its record holds. */
        fun of(conversation: ConversationId, record: CommandRecord): SearchEntry {
            val texts = listOfNotNull(record.request, record.answer) +
                record.apps.map { it.label } +
                record.data.map { it.text } +
                record.lines.map { it.text } +
                record.ledger +
                record.checklist?.items?.map { it.text }.orEmpty()
            return SearchEntry(RecordRef(conversation, record.number), record.atWall, HistoryRanking.words(texts))
        }
    }
}

/** An entry that matched, with how many of the query's words it holds. */
data class RankedEntry(val entry: SearchEntry, val score: Int)

/**
 * The search's matching and order (design 9.4 of sub-project 7), a pure function of the entries and the query, apart from
 * where the entries come from and from the details of a match, which its record gives. Texts are folded with
 * `Normalizer.words`, so letter case, diacritics and the two Serbian scripts fold as the matcher folds them; a fixed list of
 * words that hold no topic ([IGNORED]) is not counted. A query's word counts when the entry holds it, or a near miss of it:
 * a word of at least [NEAR_LETTERS] letters with the same first letter that `LabelMatcher` scores at `MatchRules.ACCEPT` or
 * more. An entry must hold at least half of the query's words; more words rank higher, and ties go to the newer entry.
 */
object HistoryRanking {
    const val NEAR_LETTERS = 4

    /** Words that hold no topic, in English and Serbian. */
    val IGNORED: Set<String> = setOf(
        "the", "a", "an", "about", "of", "to", "and", "or", "in", "on", "at", "for", "with", "from", "my", "our", "me",
        "it", "that", "this", "i", "u", "na", "o", "sa", "za", "od", "do", "je", "da", "se", "mi", "moj", "moja",
    )

    /** The folded words of [texts], which an entry holds. */
    fun words(texts: List<String>): Set<String> = texts.flatMapTo(HashSet()) { Normalizer.words(it) }

    /** The query's words that count, folded, each once; empty when [text] holds no topic. */
    fun wanted(text: String): List<String> = Normalizer.words(text).filter { it !in IGNORED }.distinct()

    /**
     * The entries that match [query], best match first and the newer first on a tie: only those given on a day from
     * [HistoryQuery.from] to [HistoryQuery.to] in [zone], both included. Empty when the query holds no word that counts.
     * Each query word's near misses are worked out once, over the distinct words of all the entries, so [similarity], the
     * matcher's score (`LabelMatcher.score`; a test may count its calls), scores each distinct word at most once per query
     * word, however many entries hold it.
     */
    fun rank(
        entries: List<SearchEntry>,
        query: HistoryQuery,
        zone: ZoneId,
        similarity: (String, String) -> Double = { wanted, word -> LabelMatcher.score(wanted, word) },
    ): List<RankedEntry> {
        val wanted = wanted(query.text)
        if (wanted.isEmpty()) return emptyList()
        val reached = entries.filter { within(it, query, zone) }
        val vocabulary = reached.flatMapTo(HashSet()) { it.words }
        val near = wanted.map { word -> vocabulary.filterTo(HashSet()) { it == word || near(word, it, similarity) } }
        return reached.mapNotNull { entry -> score(entry, near)?.let { RankedEntry(entry, it) } }
            .sortedWith(compareByDescending<RankedEntry> { it.score }.thenByDescending { it.entry.atWall })
    }

    private fun within(entry: SearchEntry, query: HistoryQuery, zone: ZoneId): Boolean {
        if (query.from == null && query.to == null) return true
        val day = Instant.ofEpochMilli(entry.atWall).atZone(zone).toLocalDate()
        return (query.from == null || day >= query.from) && (query.to == null || day <= query.to)
    }

    /** How many query words [entry] holds, each given by the words that count as it ([near]); null under half. */
    private fun score(entry: SearchEntry, near: List<Set<String>>): Int? {
        val score = near.count { counted ->
            if (counted.size <= entry.words.size) counted.any { it in entry.words } else entry.words.any { it in counted }
        }
        return score.takeIf { it * 2 >= near.size }
    }

    private fun near(wanted: String, word: String, similarity: (String, String) -> Double): Boolean =
        wanted.length >= NEAR_LETTERS && word.length >= NEAR_LETTERS && wanted[0] == word[0] &&
            similarity(wanted, word) + MatchRules.TOLERANCE >= MatchRules.ACCEPT
}