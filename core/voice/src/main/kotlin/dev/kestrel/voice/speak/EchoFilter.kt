package dev.kestrel.voice.speak

import dev.kestrel.voice.Keyword

/** A sentence that Kestrel played, from [startMillis] to [endMillis] on the elapsed-time clock. */
class Spoken(val text: String, val startMillis: Long, val endMillis: Long)

/**
 * Tells whether a keyword hit came from Kestrel's own voice (design spec, section 8.4). Kestrel knows what it said and
 * roughly when it said each word: it places a word in its sentence's playing time in proportion to the characters up to
 * the word's end. A hit is Kestrel's own voice when a word that starts with the keyword ended at most [lateMillis] before
 * the hit or at most [earlyMillis] after it, because the spotter reports a keyword a little after it ends, and the
 * estimate is rough.
 */
class EchoFilter(private val lateMillis: Long = 1_500, private val earlyMillis: Long = 1_000) {
    fun isOwnVoice(keyword: Keyword, hitMillis: Long, spoken: List<Spoken>): Boolean {
        val word = keyword.name.lowercase()
        return spoken.any { sentence ->
            WORD.findAll(sentence.text.lowercase()).any { match ->
                match.value.startsWith(word) && hitMillis - wordEnd(sentence, match.range.last + 1) in -earlyMillis..lateMillis
            }
        }
    }

    private fun wordEnd(sentence: Spoken, characters: Int): Long =
        sentence.startMillis + (sentence.endMillis - sentence.startMillis) * characters / sentence.text.length

    private companion object {
        val WORD = Regex("""\p{L}+""")
    }
}
