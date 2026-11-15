package dev.kestrel.voice.speak

/**
 * Splits a reply into sentences, so that Kestrel can speak the first one while it synthesizes the next, and can stop
 * between them (design spec, section 8.4). A sentence longer than [MAX_LENGTH] characters is split after a comma, a
 * semicolon or a colon, or else at a space.
 */
object Sentences {
    const val MAX_LENGTH = 200

    private val END = Regex("""(?<=[.!?…])\s+|\s*\n\s*""")

    fun split(text: String): List<String> =
        text.split(END).map(String::trim).filter(String::isNotEmpty).flatMap(::shorten)

    private fun shorten(sentence: String): List<String> {
        val pieces = mutableListOf<String>()
        var rest = sentence
        while (rest.length > MAX_LENGTH) {
            val head = rest.substring(0, MAX_LENGTH)
            val pause = head.indexOfLast { it == ',' || it == ';' || it == ':' }
            val space = head.lastIndexOf(' ')
            val cut = when {
                pause > 0 -> pause + 1
                space > 0 -> space
                else -> MAX_LENGTH
            }
            pieces += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) pieces += rest
        return pieces
    }
}
