package dev.kestrel.commands.edit

import dev.kestrel.commands.TextUnit

/**
 * The units of the editing commands (design spec, section 9.7): a character; a word, a run of letters and digits with
 * apostrophes inside it; a sentence, up to and including ".", "!" or "?" before whitespace or the end; a line, between
 * newlines (Kestrel cannot see where the screen wraps a line); and a paragraph, between blank lines, or the whole text
 * without one. A move forwards ends at the end of a unit and a move backwards at its start, as Ctrl and the arrow keys
 * do.
 */
object TextUnits {
    /** The units of [unit] in [text], without what separates them, from the start. Characters have none. */
    fun segments(text: String, unit: TextUnit): List<IntRange> = when (unit) {
        TextUnit.CHARACTER -> emptyList()
        TextUnit.WORD -> words(text)
        TextUnit.SENTENCE -> sentences(text)
        TextUnit.LINE -> lines(text)
        TextUnit.PARAGRAPH -> paragraphs(text)
    }

    /** The offset that [count] units from [from] lead to, forwards or backwards; it stops at the text's ends. */
    fun move(text: String, from: Int, count: Int, unit: TextUnit, forward: Boolean): Int {
        if (unit == TextUnit.CHARACTER) {
            var offset = from
            repeat(count) {
                offset = when {
                    forward && offset < text.length -> text.offsetByCodePoints(offset, 1)
                    !forward && offset > 0 -> text.offsetByCodePoints(offset, -1)
                    else -> offset
                }
            }
            return offset
        }
        val segments = segments(text, unit)
        return if (forward) {
            val ends = segments.map { it.last + 1 }.filter { it > from }
            ends.getOrElse(count - 1) { text.length }
        } else {
            val starts = segments.map { it.first }.filter { it < from }
            starts.getOrElse(starts.size - count) { 0 }
        }
    }

    /**
     * The text that [count] units cover from the cursor: backwards from the selection's start, or forwards from its end.
     * Null when there is nothing in that direction.
     */
    fun span(state: TextState, count: Int, unit: TextUnit, forward: Boolean): IntRange? {
        val range = if (forward) {
            state.selectionEnd until move(state.text, state.selectionEnd, count, unit, forward = true)
        } else {
            move(state.text, state.selectionStart, count, unit, forward = false) until state.selectionStart
        }
        return range.takeUnless { it.isEmpty() }
    }

    private fun isWordChar(text: String, index: Int): Boolean {
        val char = text[index]
        if (char.isLetterOrDigit()) return true
        // An apostrophe inside a word, as in "don't".
        return char in APOSTROPHES && index > 0 && index < text.length - 1 &&
            text[index - 1].isLetterOrDigit() && text[index + 1].isLetterOrDigit()
    }

    private fun words(text: String): List<IntRange> = buildList {
        var start = -1
        for (index in 0..text.length) {
            val inWord = index < text.length && isWordChar(text, index)
            if (inWord && start < 0) start = index
            if (!inWord && start >= 0) {
                add(start until index)
                start = -1
            }
        }
    }

    private fun sentences(text: String): List<IntRange> = buildList {
        var start = -1
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (start < 0 && !char.isWhitespace()) start = index
            if (start >= 0 && char in ENDS) {
                // Closing quotes and brackets after the mark belong to the sentence.
                var end = index + 1
                while (end < text.length && text[end] in CLOSERS) end++
                if (end == text.length || text[end].isWhitespace()) {
                    add(start until end)
                    start = -1
                    index = end
                    continue
                }
            }
            index++
        }
        if (start >= 0) add(start until text.trimEnd().length)
    }

    private fun lines(text: String): List<IntRange> = buildList {
        var start = 0
        while (true) {
            val newline = text.indexOf('\n', start)
            if (newline < 0) {
                add(start until text.length)
                return@buildList
            }
            add(start until newline)
            start = newline + 1
        }
    }

    private fun paragraphs(text: String): List<IntRange> = buildList {
        var start = 0
        for (blank in BLANK_LINES.findAll(text)) {
            addTrimmed(text, start, blank.range.first)
            start = blank.range.last + 1
        }
        addTrimmed(text, start, text.length)
    }

    private fun MutableList<IntRange>.addTrimmed(text: String, from: Int, to: Int) {
        var start = from
        var end = to
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        if (start < end) add(start until end)
    }

    private val APOSTROPHES = setOf('\'', '\u2019')
    private val ENDS = setOf('.', '!', '?')
    private val CLOSERS = setOf('"', '\'', '\u201D', '\u2019', ')', ']')
    private val BLANK_LINES = Regex("\\n[ \\t]*\\n(?:[ \\t]*\\n)*")
}
