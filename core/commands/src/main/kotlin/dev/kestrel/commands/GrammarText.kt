package dev.kestrel.commands

/**
 * The text phrases of plan 3B (design spec, section 9.7, and Appendix A: typing, punctuation, replacing, deleting, the
 * cursor, selecting, the clipboard, the keyboard, passwords and search). "Copy", "cut" and "paste" are fixed phrases
 * only, so that "copy this phone number" stays plan 3C's; no phrase here starts with "open", "call" or "dial". A payload
 * keeps the recogniser's case and the punctuation inside it.
 */
internal object GrammarText {
    private val STARTS = listOf("the beginning", "the start", "beginning", "start", "the beginning of the text", "the start of the text")
    private val ENDS = listOf("the end", "end", "the end of the text")
    private val UNITS = mapOf(
        "character" to TextUnit.CHARACTER, "characters" to TextUnit.CHARACTER, "letter" to TextUnit.CHARACTER,
        "letters" to TextUnit.CHARACTER, "word" to TextUnit.WORD, "words" to TextUnit.WORD, "sentence" to TextUnit.SENTENCE,
        "sentences" to TextUnit.SENTENCE, "line" to TextUnit.LINE, "lines" to TextUnit.LINE, "paragraph" to TextUnit.PARAGRAPH,
        "paragraphs" to TextUnit.PARAGRAPH,
    )
    private val SINGULAR_UNITS = setOf("character", "letter", "word", "sentence", "line", "paragraph")

    private val FIXED: Map<String, Command> = buildMap {
        put("start editing", TextCommand.StartEditing(null))
        for (phrase in listOf("stop editing", "stop typing", "stop dictating", "stop dictation")) put(phrase, TextCommand.StopEditing)
        for (phrase in listOf("undo", "undo that", "undo it")) put(phrase, TextCommand.Undo)
        for (phrase in listOf("redo", "redo that", "redo it")) put(phrase, TextCommand.Redo)
        for (phrase in listOf("format email", "format as email", "format email address", "format as email address", "format as an email address")) {
            put(phrase, TextCommand.FormatEmail)
        }
        for (phrase in listOf("delete", "delete that", "delete it", "backspace")) put(phrase, TextCommand.Delete(null))
        for (phrase in listOf("delete all", "delete all text", "delete all the text", "delete everything")) {
            put(phrase, TextCommand.Delete(TextScope.All))
        }
        for (phrase in listOf("delete selected text", "delete the selected text", "delete selection", "delete the selection")) {
            put(phrase, TextCommand.Delete(TextScope.Selection))
        }
        for (phrase in listOf("select all", "select all text", "select all the text", "select everything")) {
            put(phrase, TextCommand.Select(TextScope.All))
        }
        for (phrase in listOf("unselect", "unselect all", "unselect all text", "unselect text", "deselect", "deselect all", "deselect all text")) {
            put(phrase, TextCommand.Unselect)
        }
        for (start in STARTS) {
            put("delete to $start", TextCommand.Delete(TextScope.ToStart))
            put("select to $start", TextCommand.Select(TextScope.ToStart))
            for (verb in listOf("go to", "move to", "move the cursor to", "move cursor to")) put("$verb $start", TextCommand.MoveCursor(Place.Start))
        }
        for (end in ENDS) {
            put("delete to $end", TextCommand.Delete(TextScope.ToEnd))
            put("select to $end", TextCommand.Select(TextScope.ToEnd))
            for (verb in listOf("go to", "move to", "move the cursor to", "move cursor to")) put("$verb $end", TextCommand.MoveCursor(Place.End))
        }
        for (phrase in listOf("copy", "copy that", "copy it")) put(phrase, TextCommand.Clipboard(ClipboardAction.COPY))
        for (phrase in listOf("cut", "cut that", "cut it")) put(phrase, TextCommand.Clipboard(ClipboardAction.CUT))
        for (phrase in listOf("paste", "paste that", "paste it", "paste here")) put(phrase, TextCommand.Clipboard(ClipboardAction.PASTE))
        for (phrase in listOf("show keyboard", "show the keyboard")) put(phrase, TextCommand.Keyboard(show = true))
        for (phrase in listOf("hide keyboard", "hide the keyboard", "close keyboard", "close the keyboard")) {
            put(phrase, TextCommand.Keyboard(show = false))
        }
        for (phrase in listOf("new line", "newline")) put(phrase, TextCommand.NewLine)
        for (phrase in listOf("show password", "show the password", "show my password", "reveal password", "reveal the password")) {
            put(phrase, TextCommand.ShowPassword)
        }
    }

    fun fixed(words: List<String>, text: String): Command? = FIXED[words.joinToString(" ")]

    fun targeted(words: List<String>, text: String): Command? {
        if (words.size < 2) return null
        // "Delete all please": polite words after a whole command are left to the parser's second try (design D5), so that
        // they never become a phrase to find or a field's name. "Type see you now" keeps them in its payload.
        if (words.last() in CommandParser.POLITE_WORDS) {
            val inner = words.dropLastWhile { it in CommandParser.POLITE_WORDS }
            val counted = inner.size >= 2 && inner[0] in setOf("delete", "select", "highlight") && units(inner, 1) != null
            // "Delete please" is "delete"; "delete now" may still name the word "now".
            val whole = inner.joinToString(" ") in FIXED && (inner.size >= 2 || words.last() == "please")
            if (whole || counted) return null
        }
        fun cut(from: Int, to: Int = words.size) = cutWords(text, from, to)

        return when (words[0]) {
            "type" -> TextCommand.Type(cut(1))
            "start" -> if (words[1] == "editing" && words.size > 2) TargetWords.read(words, text, 2)?.let { TextCommand.StartEditing(it) } else null
            "insert" -> insert(words, text)
            "replace" -> replace(words, text)
            "capitalize", "capitalise" -> TextCommand.ChangeCase(TextCase.CAPITALIZE, cut(1))
            "uppercase" -> TextCommand.ChangeCase(TextCase.UPPER, cut(1))
            "lowercase" -> TextCommand.ChangeCase(TextCase.LOWER, cut(1))
            "upper", "lower" -> if (words[1] == "case" && words.size > 2) {
                TextCommand.ChangeCase(if (words[0] == "upper") TextCase.UPPER else TextCase.LOWER, cut(2))
            } else {
                null
            }
            "delete" -> scope(words, text, 1)?.let { TextCommand.Delete(it) }
            "select", "highlight" -> scope(words, text, 1)?.let { TextCommand.Select(it) }
            "move" -> move(words, text)
            "left", "right" -> units(words, 1)?.takeIf { !it.directed }?.let {
                TextCommand.MoveCursor(Place.Units(it.count, it.unit, forward = words[0] == "right"))
            }
            "search" -> if (words[1] == "for" && words.size > 2) TextCommand.Search(cut(2)) else null
            else -> null
        }
    }

    /** What "delete" and "select" name from the word [from] on: some units, "from X to Y", "between X and Y" or a phrase. */
    private fun scope(words: List<String>, text: String, from: Int): TextScope? {
        units(words, from)?.let { return TextScope.Units(it.count, it.unit, it.forward) }
        if (words[from] == "from") {
            val to = words.find("to", from + 2)
            if (to > 0 && to < words.size - 1) return TextScope.FromTo(cutWords(text, from + 1, to), cutWords(text, to + 1, words.size))
        }
        val between = when {
            words[from] == "between" -> from
            words[from] == "everything" && words.getOrNull(from + 1) == "between" -> from + 1
            else -> -1
        }
        if (between >= 0) {
            val and = words.find("and", between + 2)
            if (and > 0 && and < words.size - 1) {
                return TextScope.Between(cutWords(text, between + 1, and), cutWords(text, and + 1, words.size))
            }
        }
        return TextScope.Phrase(cutWords(text, from, words.size))
    }

    private class Units(val count: Int, val unit: TextUnit, val forward: Boolean, val directed: Boolean)

    /**
     * Some units from the word [from] to the end: "three words", "the next two sentences", "the last line", "a word" or
     * "word". "The word" and "words" alone are phrases. "Next" goes forwards; a bare count, "previous" and "last" go
     * backwards.
     */
    private fun units(words: List<String>, from: Int): Units? {
        val unit = UNITS[words.last()] ?: return null
        var index = from
        val article = words.getOrNull(index) == "the"
        if (article) index++
        val direction = words.getOrNull(index)
        val directed = direction in setOf("next", "previous", "last")
        if (directed) index++
        val countWords = words.subList(index, words.size - 1)
        val count = when {
            countWords.isEmpty() -> if (directed || (!article && words.last() in SINGULAR_UNITS)) 1 else return null
            countWords == listOf("a") || countWords == listOf("one") -> 1
            // Right after the verb, a lone "to" is 2 and "for" is 4, as the recogniser writes them (design D5).
            else -> SpokenNumbers.number(countWords, lone = true)?.takeIf { it > 0 } ?: return null
        }
        return Units(count, unit, forward = direction == "next", directed = directed)
    }

    /** "Insert X before Y", "insert X after Y", "insert X between Y and Z", "insert X at the beginning" or "at the end". */
    private fun insert(words: List<String>, text: String): Command? {
        val between = words.lastIndexOf("between")
        if (between >= 2) {
            val and = words.find("and", between + 2)
            if (and > 0 && and < words.size - 1) {
                val place = Place.Between(cutWords(text, between + 1, and), cutWords(text, and + 1, words.size))
                return TextCommand.Insert(cutWords(text, 1, between), place)
            }
        }
        val split = words.indices.lastOrNull { it >= 2 && it < words.size - 1 && (words[it] == "before" || words[it] == "after") }
        if (split != null) {
            val phrase = cutWords(text, split + 1, words.size)
            val place = if (words[split] == "before") Place.Before(phrase) else Place.After(phrase)
            return TextCommand.Insert(cutWords(text, 1, split), place)
        }
        for ((places, place) in listOf(STARTS to Place.Start, ENDS to Place.End)) {
            for (ending in places) {
                val tail = listOf("at") + ending.split(' ')
                val at = words.size - tail.size
                if (at >= 2 && words.subList(at, words.size) == tail) return TextCommand.Insert(cutWords(text, 1, at), place)
            }
        }
        return null
    }

    /** "Replace X with Y" (at the first "with"), and "replace everything between X and Y with Z". */
    private fun replace(words: List<String>, text: String): Command? {
        val between = when {
            words[1] == "between" -> 1
            words[1] in setOf("everything", "all") && words.getOrNull(2) == "between" -> 2
            else -> -1
        }
        if (between > 0) {
            val and = words.find("and", between + 2)
            val with = if (and > 0) words.find("with", and + 2) else -1
            if (with > 0 && with < words.size - 1) {
                return TextCommand.ReplaceBetween(
                    cutWords(text, between + 1, and),
                    cutWords(text, and + 1, with),
                    cutWords(text, with + 1, words.size),
                )
            }
        }
        val with = words.find("with", 2)
        if (with < 0 || with == words.size - 1) return null
        return TextCommand.Replace(cutWords(text, 1, with), cutWords(text, with + 1, words.size))
    }

    /** "Move after X", "move before X", "move between X and Y", also with "the cursor" or "cursor" after "move". */
    private fun move(words: List<String>, text: String): Command? {
        var index = 1
        if (words.getOrNull(index) == "the" && words.getOrNull(index + 1) == "cursor") index += 2
        else if (words.getOrNull(index) == "cursor") index++
        if (index >= words.size - 1) return null
        val rest = index + 1
        return when (words[index]) {
            "after" -> TextCommand.MoveCursor(Place.After(cutWords(text, rest, words.size)))
            "before" -> TextCommand.MoveCursor(Place.Before(cutWords(text, rest, words.size)))
            "between" -> {
                val and = words.find("and", rest + 1)
                if (and > 0 && and < words.size - 1) {
                    TextCommand.MoveCursor(Place.Between(cutWords(text, rest, and), cutWords(text, and + 1, words.size)))
                } else {
                    null
                }
            }
            else -> null
        }
    }

    /** The index of the first [word] at [from] or after it, or -1. */
    private fun List<String>.find(word: String, from: Int): Int = (from until size).firstOrNull { this[it] == word } ?: -1
}
