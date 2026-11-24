package dev.kestrel.commands.text

import kotlin.math.abs

/** Something that the owner may name: an element on the screen, an app or a contact, with all its names. */
data class MatchCandidate(val id: String, val names: List<String>) {
    override fun toString() = "MatchCandidate(id of ${id.length} characters, ${names.size} names)"
}

enum class Strictness { NORMAL, STRICT }

sealed interface MatchDecision {
    data class Found(val id: String) : MatchDecision {
        override fun toString() = "Found(id of ${id.length} characters)"
    }

    data class Ambiguous(val ids: List<String>) : MatchDecision {
        override fun toString() = "Ambiguous(${ids.size} candidates)"
    }

    data object NotFound : MatchDecision
}

/**
 * Matches spoken words to names (design spec, section 9.6; research r23). It folds both sides with [Normalizer] and
 * scores them with the joined Jaro–Winkler similarity, the word overlap and the phonetic key.
 */
object LabelMatcher {
    private val ARTICLES = setOf("the", "a", "an")

    // The score of an exact match after folding, also with the words joined; no other match reaches it.
    private const val EXACT = 1.0

    /**
     * How well [spoken] names [name], from 0.0 to 1.0. With [Strictness.STRICT], two words never count as the same for
     * their phonetic key.
     */
    fun score(spoken: String, name: String, strictness: Strictness = Strictness.NORMAL): Double {
        val folded = Normalizer.withOffsets(spoken)
        val all = folded.words.map { folded.text.substring(it) }
        val articles = all.takeWhile { it in ARTICLES }.size.takeIf { it < all.size } ?: 0
        val said = all.drop(articles)
        val named = Normalizer.words(name)
        if (said.isEmpty() || named.isEmpty()) return 0.0
        val joined = said.joinToString("")
        val namedJoined = named.joinToString("")
        // A name may start with an article itself, as "The Guardian" does.
        if (joined == namedJoined || all.joinToString("") == namedJoined) return EXACT
        val saidText = spoken.substring(folded.span(articles, all.size))
        val total = MatchRules.JOINED_WEIGHT * JaroWinkler.similarity(joined, namedJoined) +
            MatchRules.WORDS_WEIGHT * overlap(said, named, phoneticWords = strictness == Strictness.NORMAL) +
            MatchRules.PHONETIC_WEIGHT * phonetic(saidText, name)
        val prefix = listOf(said, all).any { it.size < named.size && named.subList(0, it.size) == it }
        return if (prefix) maxOf(total, MatchRules.PREFIX_SCORE) else total
    }

    /** Whether [spoken] names [name] exactly, as [decide] counts an exact match: the same words after folding, also joined. */
    fun exact(spoken: String, name: String): Boolean = score(spoken, name) == EXACT

    /**
     * Decides which of [candidates] [spoken] names: [MatchDecision.Found] for the only exact match or else a clear winner,
     * [MatchDecision.Ambiguous] for two or more exact matches or else the close ones, and [MatchDecision.NotFound] when no
     * candidate comes close.
     */
    fun decide(spoken: String, candidates: List<MatchCandidate>, strictness: Strictness): MatchDecision {
        val ranked = candidates.mapNotNull { candidate ->
            candidate.names.map { Scored(candidate.id, score(spoken, it, strictness), it) }.maxByOrNull { it.score }
        }.sortedByDescending { it.score }
        val best = ranked.firstOrNull() ?: return MatchDecision.NotFound
        // The only name that the owner said exactly acts, whatever near misses the screen also shows.
        val exact = ranked.filter { it.score == EXACT }
        if (exact.size == 1) return MatchDecision.Found(exact[0].id)
        if (exact.size > 1) return MatchDecision.Ambiguous(exact.take(MatchRules.MAX_AMBIGUOUS).map { it.id })
        val second = ranked.getOrNull(1)?.score
        val strict = strictness == Strictness.STRICT || letters(spoken) <= MatchRules.SHORT_LETTERS ||
            letters(best.name) <= MatchRules.SHORT_LETTERS
        if (accepts(best.score, second, strict)) return MatchDecision.Found(best.id)
        if (best.score + MatchRules.TOLERANCE < MatchRules.CLOSE) return MatchDecision.NotFound
        val close = ranked.filter {
            it.score + MatchRules.TOLERANCE >= MatchRules.CLOSE && best.score - it.score <= MatchRules.NEAR + MatchRules.TOLERANCE
        }
        return MatchDecision.Ambiguous(close.take(MatchRules.MAX_AMBIGUOUS).map { it.id })
    }

    /** Whether a best score of [best], with [second] behind it, acts. Two equal best scores never act. */
    internal fun accepts(best: Double, second: Double?, strict: Boolean): Boolean {
        val accept = if (strict) MatchRules.STRICT_ACCEPT else MatchRules.ACCEPT
        val margin = if (strict) MatchRules.STRICT_MARGIN else MatchRules.MARGIN
        if (second != null && abs(best - second) <= MatchRules.TOLERANCE) return false
        return best + MatchRules.TOLERANCE >= accept && (second == null || best - second + MatchRules.TOLERANCE >= margin)
    }

    private class Scored(val id: String, val score: Double, val name: String)

    // The Dice coefficient over words; each word of the name matches at most one spoken word.
    private fun overlap(said: List<String>, named: List<String>, phoneticWords: Boolean): Double {
        val used = BooleanArray(named.size)
        var pairs = 0
        for (word in said) {
            val index = named.indices.firstOrNull { !used[it] && sameWord(word, named[it], phoneticWords) } ?: continue
            used[index] = true
            pairs++
        }
        return 2.0 * pairs / (said.size + named.size)
    }

    private fun sameWord(a: String, b: String, phoneticWords: Boolean): Boolean =
        a == b || stem(a) == stem(b) || JaroWinkler.similarity(a, b) >= MatchRules.WORD_SIMILARITY ||
            (phoneticWords && soundTheSame(a, b))

    // This plan's addition to design D4: "veiber" is "Viber". The respelled forms must be similar too, so that the key
    // never accepts a word on its own ("later" is not "Letter").
    private fun soundTheSame(a: String, b: String): Boolean =
        minOf(a.length, b.length) >= MatchRules.PHONETIC_WORD_LETTERS && PhoneticKey.of(a) == PhoneticKey.of(b) &&
            JaroWinkler.similarity(PhoneticKey.spelled(a), PhoneticKey.spelled(b)) + MatchRules.TOLERANCE >=
            MatchRules.PHONETIC_WORD_SIMILARITY

    private fun stem(word: String): String = if (word.length > 3 && word.endsWith('s')) word.dropLast(1) else word

    private fun phonetic(a: String, b: String): Double {
        val first = PhoneticKey.of(a)
        val second = PhoneticKey.of(b)
        if (first.isEmpty() && second.isEmpty()) return 1.0
        if (first.isEmpty() || second.isEmpty()) return 0.0
        return 1.0 - editDistance(first, second).toDouble() / maxOf(first.length, second.length)
    }

    private fun letters(text: String): Int {
        val words = Normalizer.words(text)
        return words.dropWhile { it in ARTICLES }.ifEmpty { words }.sumOf { it.length }
    }

    private fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.length + 1)
            current[0] = i + 1
            for (j in b.indices) {
                current[j + 1] = minOf(previous[j + 1] + 1, current[j] + 1, previous[j] + if (a[i] == b[j]) 0 else 1)
            }
            previous = current
        }
        return previous[b.length]
    }
}
