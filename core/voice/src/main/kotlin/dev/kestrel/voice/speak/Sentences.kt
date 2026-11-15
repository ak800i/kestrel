package dev.kestrel.voice.speak

/**
 * Splits a reply into sentences, so that Kestrel can speak the first one while it synthesizes the next, and can stop
 * between them (design spec, section 8.4).
 *
 * A sentence ends after ".", "!", "?" or "…", which a closing quote or bracket may follow, when whitespace and then a
 * capital letter, a digit, or an opening quote or bracket come next. So "7 a.m. tomorrow" and "e.g. an apple" stay whole.
 * A line break also ends a sentence.
 *
 * A sentence longer than [MAX_LENGTH] characters is split after the last comma, semicolon or colon that whitespace
 * follows, so that "10:15" and "1,250" stay whole, or else at the last whitespace. A run of more than [MAX_LENGTH]
 * characters without whitespace is cut after [MAX_LENGTH] characters, or one character earlier when the cut would split
 * a surrogate pair.
 */
object Sentences {
    const val MAX_LENGTH = 200

    private val END = Regex("""(?<=[.!?…]|[.!?…]["'”’)\]])[\s\u00A0\u202F]+(?=[\p{Lu}\p{N}"'“‘(])|\s*\n\s*""")

    fun split(text: String): List<String> =
        text.split(END).map(String::trim).filter(String::isNotEmpty).flatMap(::shorten)

    private fun shorten(sentence: String): List<String> {
        val pieces = mutableListOf<String>()
        var rest = sentence
        while (rest.length > MAX_LENGTH) {
            val pause = (MAX_LENGTH - 1 downTo 1).firstOrNull { rest[it] in ",;:" && rest[it + 1].isWhitespace() } ?: -1
            val space = rest.substring(0, MAX_LENGTH).indexOfLast { it.isWhitespace() }
            val cut = when {
                pause > 0 -> pause + 1
                space > 0 -> space
                rest[MAX_LENGTH - 1].isHighSurrogate() -> MAX_LENGTH - 1
                else -> MAX_LENGTH
            }
            pieces += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) pieces += rest
        return pieces
    }
}
