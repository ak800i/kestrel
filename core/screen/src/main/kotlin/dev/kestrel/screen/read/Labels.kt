package dev.kestrel.screen.read

/** How Kestrel names elements (design spec, sections 9.2 and 9.4). */
object Labels {
    private const val MAX_LENGTH = 80
    private val whitespace = Regex("\\s+")
    private val camelHump = Regex("(?<=[a-z0-9])(?=[A-Z])")
    private val genericWords = setOf(
        "btn", "button", "fab", "ib", "ic", "icon", "image", "img", "imageview", "iv", "layout", "text", "textview", "tv", "view",
    )

    /** Collapses runs of whitespace into one space, or returns null for blank text. */
    fun clean(text: CharSequence?): String? = text?.toString()?.replace(whitespace, " ")?.trim()?.takeIf { it.isNotEmpty() }

    /** Joins texts found inside an element into one label of at most 80 characters. */
    fun join(pieces: List<String>): String? = clean(pieces.joinToString(" "))?.take(MAX_LENGTH)?.trimEnd()

    /** Derives a name from a view ID: `dev.app:id/compose_button` becomes "compose", and `btnSendMessage` "send message". */
    fun suggestedName(viewId: String?): String? {
        val name = viewId?.substringAfter(":id/") ?: return null
        val words = name.split('_', '-', '.')
            .flatMap { it.split(camelHump) }
            .map { it.lowercase() }
            .filter { it.isNotEmpty() && it !in genericWords && !it.all(Char::isDigit) }
        return words.joinToString(" ").ifEmpty { null }
    }
}
