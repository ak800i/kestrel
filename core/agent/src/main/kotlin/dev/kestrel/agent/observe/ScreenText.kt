package dev.kestrel.agent.observe

/**
 * How screen text goes into a request (design D8 of sub-project 5; spec 11.3): in double quotes, escaped so that it can
 * never close its quote or start a line of its own, and cut with "…".
 */
object ScreenText {
    /** A label is cut at 80 characters. */
    const val LABEL_LIMIT = 80

    /** A field's value and a message's text are cut at 300 characters. */
    const val VALUE_LIMIT = 300

    /** A row with the text of its children is cut at 120 characters. */
    const val ROW_LIMIT = 120

    private val SHORT_CLASSES = mapOf(
        "TextView" to "Text",
        "ImageView" to "Image",
        "RecyclerView" to "List",
        "ListView" to "List",
        "GridView" to "List",
    )

    /**
     * `\` becomes `\\`, `"` becomes `\"`, every line break (LF, CR, CR LF, U+2028, U+2029) becomes `\n` and a tab a
     * space; other control characters, zero-width characters, bidirectional controls, Unicode tags and private-use
     * characters are removed.
     */
    fun escape(text: String): String {
        val out = StringBuilder(text.length + 8)
        var index = 0
        while (index < text.length) {
            val point = text.codePointAt(index)
            val size = Character.charCount(point)
            when {
                point == '\\'.code -> out.append("\\\\")
                point == '"'.code -> out.append("\\\"")
                point == '\r'.code -> {
                    out.append("\\n")
                    if (text.getOrNull(index + 1) == '\n') index++
                }
                point == '\n'.code || point == 0x2028 || point == 0x2029 -> out.append("\\n")
                point == '\t'.code -> out.append(' ')
                removed(point) -> Unit
                else -> out.appendCodePoint(point)
            }
            index += size
        }
        return out.toString()
    }

    private fun removed(point: Int): Boolean =
        point < 0x20 || point in 0x7F..0x9F ||
            point in 0x200B..0x200F || point == 0x2060 || point == 0xFEFF ||
            point in 0x202A..0x202E || point in 0x2066..0x2069 ||
            point in 0xE0000..0xE007F || point in 0xE000..0xF8FF || point >= 0xF0000

    /** [text] cut at [limit] characters with "…", never inside a surrogate pair. */
    fun cut(text: String, limit: Int): String {
        if (text.length <= limit) return text
        var end = limit
        if (end > 0 && Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end).trimEnd() + "…"
    }

    fun quote(text: String, limit: Int): String = "\"" + escape(cut(text, limit)) + "\""

    /**
     * [text] quoted and escaped, but never cut: the owner's own words, whose length his input bounds (design 7.3 of
     * sub-project 7), and a resumed goal's notes of what Kestrel did, which a cut could make wrong (design 7.6 of
     * sub-project 7).
     */
    fun quoteWhole(text: String): String = "\"" + escape(text) + "\""

    /** An estimate of the tokens: characters ÷ 3.5, or ÷ 2.5 for Cyrillic characters, rounded up. */
    fun tokens(text: String): Int {
        val cyrillic = text.count { it in '\u0400'..'\u04FF' }
        return kotlin.math.ceil((text.length - cyrillic) / 3.5 + cyrillic / 2.5).toInt()
    }

    /**
     * The class's short name in an observation: `Button`, `Text`, `List`, `View` for none. An app can give its nodes any
     * class name, so only letters, digits, `_` and `$` stay, at most 80 of them, and the name can never start a line or
     * close a quote (review 3 I1).
     */
    fun shortClass(className: String?): String {
        val simple = className?.substringAfterLast('.')?.filter { it.isLetterOrDigit() || it == '_' || it == '$' }?.take(LABEL_LIMIT)
            ?.takeIf { it.isNotEmpty() } ?: return "View"
        return SHORT_CLASSES[simple] ?: simple
    }
}
