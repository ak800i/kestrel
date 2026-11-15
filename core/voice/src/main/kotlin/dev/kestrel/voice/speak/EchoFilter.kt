package dev.kestrel.voice.speak

import dev.kestrel.voice.Keyword

/**
 * A sentence that Kestrel played, from [startMillis] to [endMillis] on the elapsed-time clock
 * (`SystemClock.elapsedRealtime()`).
 */
class Spoken(val text: String, val startMillis: Long, val endMillis: Long)

/**
 * Tells whether a keyword hit came from Kestrel's own voice (design spec, section 8.4). Kestrel knows what it said and
 * roughly when it said it.
 *
 * The filter looks for the keyword in a sentence's letters and digits, with everything else removed, so the keyword
 * also counts inside a word or across words: "nonstop", "unstoppable" and "It's top-rated" contain "stop". This makes
 * the filter ignore more of the owner's keywords while Kestrel says such a word, but that costs less than Kestrel
 * stopping itself and cancelling a task, because the owner can simply say the keyword again.
 *
 * It places the end of the keyword's letters in the sentence's playing time, in proportion to the characters up to that
 * point. A digit counts as three characters, because Piper speaks digits as words. A hit is Kestrel's own voice
 * when the keyword ended at most [lateMillis] before the hit or at most [earlyMillis] after it, because the spotter
 * reports a keyword a little after it ends, and the estimate is rough. Both margins must be 0 or more.
 */
class EchoFilter(private val lateMillis: Long = 1_500, private val earlyMillis: Long = 1_000) {
    init {
        require(lateMillis >= 0 && earlyMillis >= 0) { "The margins must be 0 or more" }
    }

    /**
     * Whether the [keyword] that the spotter heard at [hitMillis] came from one of the [spoken] sentences. [hitMillis]
     * must come from the same clock as [Spoken]'s times, `SystemClock.elapsedRealtime()`.
     */
    fun isOwnVoice(keyword: Keyword, hitMillis: Long, spoken: List<Spoken>): Boolean {
        val word = keyword.name.lowercase()
        return spoken.any { sentence ->
            keywordEnds(sentence.text, word).any { end ->
                val sinceEnd = hitMillis - estimatedTime(sentence, end)
                sinceEnd in -earlyMillis..lateMillis
            }
        }
    }

    /** The positions in [text] just after each match of [word] in the text's lowercased letters and digits. */
    private fun keywordEnds(text: String, word: String): List<Int> {
        val positions = text.indices.filter { text[it].isLetterOrDigit() }
        val letters = String(CharArray(positions.size) { text[positions[it]].lowercaseChar() })
        val ends = mutableListOf<Int>()
        var start = letters.indexOf(word)
        while (start >= 0) {
            ends += positions[start + word.length - 1] + 1
            start = letters.indexOf(word, start + 1)
        }
        return ends
    }

    private fun estimatedTime(sentence: Spoken, characters: Int): Long =
        sentence.startMillis +
            (sentence.endMillis - sentence.startMillis) * weight(sentence.text.subSequence(0, characters)) /
            weight(sentence.text)

    private fun weight(text: CharSequence): Int = text.length + (DIGIT_WEIGHT - 1) * text.count(Char::isDigit)

    private companion object {
        const val DIGIT_WEIGHT = 3
    }
}
