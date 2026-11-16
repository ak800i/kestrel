package dev.kestrel.voice.sherpa

/**
 * Puts boost words into sherpa-onnx's format for one utterance: phrases separated by "/" (design spec, section 8.3, B39).
 * "/" separates phrases and ":" would start a boost of its own, so every character other than a letter, a digit, an
 * apostrophe or a hyphen becomes a space. Words without a letter or a digit, such as "-", are dropped, because the model
 * would read them as a strong boost for a piece of punctuation. Phrases with a character that the model cannot spell
 * (one that is not a word piece of its own, such as a letter of another script; see [characters]) are dropped, because
 * sherpa-onnx may log every boost word when it cannot encode one (section 13.5); the model could not write such a phrase
 * anyway. Empty phrases, phrases longer than [MAX_LENGTH] characters and repeated phrases (ignoring case) are dropped,
 * and at most [MAX_PHRASES] phrases are kept.
 */
internal object BoostWords {
    const val MAX_PHRASES = 100
    const val MAX_LENGTH = 40

    /** The word-start marker of SentencePiece, which begins a piece at the start of a word. */
    private const val WORD_START = '▁'

    /** Formats [phrases], keeping only those whose characters are all in [spelling] (see [characters]). */
    fun format(phrases: List<String>, spelling: Set<Char>): String = phrases.asSequence()
        .map(::clean)
        .filter { it.isNotEmpty() && it.length <= MAX_LENGTH }
        .filter { phrase -> phrase.all { it == ' ' || it in spelling } }
        .distinctBy { it.lowercase() }
        .take(MAX_PHRASES)
        .joinToString("/")

    /**
     * The characters that the model can spell: each one that is a word piece of [tokens], the text of the model's
     * tokens.txt, on its own or after the word-start marker. A character that appears only inside a longer piece is not
     * one of them, because sherpa-onnx encodes a boost word piece by piece. When the marker is not a piece of its own, the
     * encoder cannot start a word, so the set is empty and every phrase is dropped.
     */
    fun characters(tokens: String): Set<Char> {
        val pieces = BpeVocab.pieces(tokens).toList()
        if (WORD_START.toString() !in pieces) return emptySet()
        return pieces.map { it.removePrefix(WORD_START.toString()) }.filter { it.length == 1 }.map { it.single() }.toSet()
    }

    private fun clean(phrase: String): String = phrase
        .map { if (it.isLetterOrDigit() || it == '\'' || it == '-') it else ' ' }
        .joinToString("")
        .split(' ')
        .filter { word -> word.any { it.isLetterOrDigit() } }
        .joinToString(" ")
}
