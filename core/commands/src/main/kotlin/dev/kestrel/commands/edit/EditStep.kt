package dev.kestrel.commands.edit

/** What an editing command does to a field (design spec, section 9.7). Every offset is computed by Kestrel itself. */
sealed interface EditStep {
    /** Replaces the text from [start] until [end] by [replacement], then selects [selectionStart] to [selectionEnd]. */
    data class Change(
        val start: Int,
        val end: Int,
        val replacement: String,
        val selectionStart: Int,
        val selectionEnd: Int,
    ) : EditStep {
        /** The text after this change. */
        fun applyTo(text: String): String = text.substring(0, start) + replacement + text.substring(end)

        override fun toString() =
            "Change($start to $end with ${replacement.length} characters, selection $selectionStart to $selectionEnd)"
    }

    /** Selects [start] to [end]; equal offsets put the cursor there. */
    data class Select(val start: Int, val end: Int) : EditStep

    /** The command cannot act on this text. [phrase] is the owner's words that the text does not contain. */
    data class Fail(val reason: EditFailure, val phrase: String? = null) : EditStep {
        override fun toString() = "Fail($reason, ${phrase?.let { "phrase of ${it.length} characters" } ?: "no phrase"})"
    }
}

/** Why an editing command cannot act. */
enum class EditFailure {
    /** "Delete selected text" while nothing is selected. */
    NOTHING_SELECTED,

    /** The text does not contain the phrase that the command names. */
    NOT_FOUND,

    /** There is nothing to delete, select or move over in that direction. */
    NOTHING_THERE,

    /** A text command that the editing model does not plan, such as "undo" or "copy". */
    NOT_AN_EDIT,

    /**
     * The new words of "insert", "replace" or "replace everything between" hold a spoken "new line" or "new paragraph". A
     * line break goes only through the Enter key, which a single-line field refuses.
     */
    LINE_BREAK,
}
