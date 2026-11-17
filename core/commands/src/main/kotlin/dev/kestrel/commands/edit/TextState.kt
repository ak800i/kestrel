package dev.kestrel.commands.edit

/**
 * A text field's text and selection, as the editing model sees them (design spec, section 9.7). The selection runs from
 * [selectionStart] to [selectionEnd]; when they are equal, it is the cursor.
 */
data class TextState(val text: String, val selectionStart: Int, val selectionEnd: Int) {
    init {
        require(selectionStart in 0..selectionEnd && selectionEnd <= text.length) { "The selection lies outside the text" }
    }

    val hasSelection: Boolean get() = selectionStart < selectionEnd

    override fun toString() = "TextState(${text.length} characters, selection $selectionStart to $selectionEnd)"

    companion object {
        /**
         * The state of a field as Android reports it: a negative selection is a cursor at the end, a reversed one is
         * turned round, and offsets beyond the text end at the text's end.
         */
        fun of(text: String, selectionStart: Int, selectionEnd: Int): TextState {
            if (selectionStart < 0 || selectionEnd < 0) return TextState(text, text.length, text.length)
            val start = minOf(selectionStart, selectionEnd).coerceAtMost(text.length)
            val end = maxOf(selectionStart, selectionEnd).coerceAtMost(text.length)
            return TextState(text, start, end)
        }
    }
}
