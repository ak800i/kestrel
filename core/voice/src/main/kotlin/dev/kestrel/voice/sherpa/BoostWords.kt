package dev.kestrel.voice.sherpa

/**
 * Puts boost words into sherpa-onnx's format for one utterance: phrases separated by "/" (design spec, section 8.3, B39).
 * "/" separates phrases and ":" would start a boost of its own, so every character other than a letter, a digit, an
 * apostrophe or a hyphen becomes a space. Words without a letter or a digit, such as "-", are dropped, because the model
 * would read them as a strong boost for a piece of punctuation. Empty phrases, phrases longer than [MAX_LENGTH]
 * characters and repeated phrases (ignoring case) are dropped, and at most [MAX_PHRASES] phrases are kept. Phrases in
 * other scripts or with emoji were harmless in tests, because sherpa-onnx skips what the model cannot spell.
 */
internal object BoostWords {
    const val MAX_PHRASES = 100
    const val MAX_LENGTH = 40

    fun format(phrases: List<String>): String = phrases.asSequence()
        .map(::clean)
        .filter { it.isNotEmpty() && it.length <= MAX_LENGTH }
        .distinctBy { it.lowercase() }
        .take(MAX_PHRASES)
        .joinToString("/")

    private fun clean(phrase: String): String = phrase
        .map { if (it.isLetterOrDigit() || it == '\'' || it == '-') it else ' ' }
        .joinToString("")
        .split(' ')
        .filter { word -> word.any { it.isLetterOrDigit() } }
        .joinToString(" ")
}
