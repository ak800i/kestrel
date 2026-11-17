package dev.kestrel.commands

/** The units of the text commands (design spec, section 9.7, and Appendix A). */
enum class TextUnit { CHARACTER, WORD, SENTENCE, LINE, PARAGRAPH }

/** What "delete X" and "select X" act on. */
sealed interface TextScope {
    data object Selection : TextScope

    data object All : TextScope

    data object ToStart : TextScope

    data object ToEnd : TextScope

    data class Phrase(val text: String) : TextScope {
        override fun toString() = "Phrase(${text.length} characters)"
    }

    /** From [from] to [to], both included. */
    data class FromTo(val from: String, val to: String) : TextScope {
        override fun toString() = "FromTo(${from.length} and ${to.length} characters)"
    }

    /** Between [from] and [to], both left out. */
    data class Between(val from: String, val to: String) : TextScope {
        override fun toString() = "Between(${from.length} and ${to.length} characters)"
    }

    /** [count] units, backwards from the cursor unless [forward]. */
    data class Units(val count: Int, val unit: TextUnit, val forward: Boolean) : TextScope
}

/** Where "insert" and "move" put the cursor or the text. */
sealed interface Place {
    data object Start : Place

    data object End : Place

    data class Before(val phrase: String) : Place {
        override fun toString() = "Before(${phrase.length} characters)"
    }

    data class After(val phrase: String) : Place {
        override fun toString() = "After(${phrase.length} characters)"
    }

    data class Between(val first: String, val second: String) : Place {
        override fun toString() = "Between(${first.length} and ${second.length} characters)"
    }

    data class Units(val count: Int, val unit: TextUnit, val forward: Boolean) : Place
}

enum class TextCase { CAPITALIZE, UPPER, LOWER }

enum class ClipboardAction { CUT, COPY, PASTE }

/** The text commands of plan 3B (design spec, section 9.7, and Appendix A). */
sealed interface TextCommand : Command {
    data class StartEditing(val target: Target?) : TextCommand

    data object StopEditing : TextCommand

    data class Type(val text: String) : TextCommand {
        override fun toString() = "Type(${text.length} characters)"
    }

    data object Undo : TextCommand

    data object Redo : TextCommand

    data class Insert(val text: String, val place: Place) : TextCommand {
        override fun toString() = "Insert(${text.length} characters, $place)"
    }

    data class Replace(val old: String, val new: String) : TextCommand {
        override fun toString() = "Replace(${old.length} with ${new.length} characters)"
    }

    data class ReplaceBetween(val from: String, val to: String, val new: String) : TextCommand {
        override fun toString() = "ReplaceBetween(${from.length} and ${to.length} with ${new.length} characters)"
    }

    data class ChangeCase(val case: TextCase, val phrase: String) : TextCommand {
        override fun toString() = "ChangeCase($case, ${phrase.length} characters)"
    }

    data object FormatEmail : TextCommand

    /** A bare "delete" has no scope: it deletes the selection, or the word before the cursor. */
    data class Delete(val scope: TextScope?) : TextCommand

    data class MoveCursor(val place: Place) : TextCommand

    data class Select(val scope: TextScope) : TextCommand

    data object Unselect : TextCommand

    data class Clipboard(val action: ClipboardAction) : TextCommand

    data class Keyboard(val show: Boolean) : TextCommand

    data object NewLine : TextCommand

    data object ShowPassword : TextCommand

    data class Search(val query: String) : TextCommand {
        override fun toString() = "Search(${query.length} characters)"
    }
}
