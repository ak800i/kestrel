package dev.kestrel.commands.edit

import dev.kestrel.commands.text.JaroWinkler
import dev.kestrel.commands.text.Normalizer

/**
 * Finds a spoken phrase in a field's text (design spec, section 9.7). It compares the phrase's words with the text's
 * words after normalising both (section 9.6), and maps a hit back to the text's own offsets. Without an exact hit, a
 * run of as many words whose average Jaro–Winkler similarity is at least [SIMILAR] counts; without any hit, the phrase
 * is tried once more without a leading "the", "a" or "an".
 */
object TextSearch {
    const val SIMILAR = 0.85

    /**
     * The hit of [phrase] in [text] nearest to [cursor]: one that contains the cursor, else the one that ends nearest
     * before it, else the nearest after it. Null when the text does not contain the phrase.
     */
    fun find(text: String, phrase: String, cursor: Int): IntRange? = nearest(all(text, phrase), cursor)

    /** Every hit of [phrase] in [text], from the start, as ranges of [text]. */
    fun all(text: String, phrase: String): List<IntRange> {
        val words = Normalizer.words(phrase)
        if (words.isEmpty()) return emptyList()
        val hits = hits(text, words)
        if (hits.isNotEmpty() || words.size < 2 || words[0] !in ARTICLES) return hits
        return hits(text, words.drop(1))
    }

    /** Of [hits], the one that contains [cursor], else the one that ends nearest before it, else the nearest after it. */
    fun nearest(hits: List<IntRange>, cursor: Int): IntRange? =
        hits.firstOrNull { it.first < cursor && it.last + 1 > cursor }
            ?: hits.filter { it.last + 1 <= cursor }.maxByOrNull { it.last }
            ?: hits.filter { it.first >= cursor }.minByOrNull { it.first }

    private fun hits(text: String, words: List<String>): List<IntRange> {
        val folded = Normalizer.withOffsets(text)
        val fieldWords = folded.words.map { folded.text.substring(it.first, it.last + 1) }
        val starts = 0..fieldWords.size - words.size
        val exact = starts.filter { start -> words.indices.all { fieldWords[start + it] == words[it] } }
        val found = exact.ifEmpty {
            starts.filter { start ->
                words.indices.sumOf { JaroWinkler.similarity(fieldWords[start + it], words[it]) } / words.size >= SIMILAR
            }
        }
        return found.map { folded.span(it, it + words.size) }
    }

    private val ARTICLES = setOf("the", "a", "an")
}
