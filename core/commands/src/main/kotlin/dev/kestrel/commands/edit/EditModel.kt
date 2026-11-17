package dev.kestrel.commands.edit

import dev.kestrel.commands.Place
import dev.kestrel.commands.TextCase
import dev.kestrel.commands.TextCommand
import dev.kestrel.commands.TextScope
import dev.kestrel.commands.TextUnit
import java.util.Locale

/**
 * Plans the editing commands (design spec, section 9.7): every offset, move and range comes from the field's text and
 * selection, never from Android's movement actions, which WebView and Chrome take from their own cursor. A phrase is
 * found with [TextSearch], nearest to the cursor.
 */
object EditModel {
    fun plan(command: TextCommand, state: TextState): EditStep = when (command) {
        is TextCommand.Delete -> delete(command.scope, state)
        is TextCommand.Select -> select(command.scope, state)
        TextCommand.Unselect -> EditStep.Select(state.selectionEnd, state.selectionEnd)
        is TextCommand.MoveCursor -> move(command.place, state)
        is TextCommand.Insert -> insert(command.text, command.place, state)
        is TextCommand.Replace -> replace(command.old, command.new, state)
        is TextCommand.ReplaceBetween -> replaceBetween(command, state)
        is TextCommand.ChangeCase -> changeCase(command.case, command.phrase, state)
        TextCommand.FormatEmail -> formatEmail(state)
        else -> EditStep.Fail(EditFailure.NOT_AN_EDIT)
    }

    /** The change that turns [from] into [to]: only the range that differs, then [to]'s selection. Undo and redo use it. */
    fun between(from: String, to: TextState): EditStep.Change {
        val shorter = minOf(from.length, to.text.length)
        var prefix = 0
        while (prefix < shorter && from[prefix] == to.text[prefix]) prefix++
        var suffix = 0
        while (suffix < shorter - prefix && from[from.length - 1 - suffix] == to.text[to.text.length - 1 - suffix]) suffix++
        val replacement = to.text.substring(prefix, to.text.length - suffix)
        return EditStep.Change(prefix, from.length - suffix, replacement, to.selectionStart, to.selectionEnd)
    }

    private fun delete(scope: TextScope?, state: TextState): EditStep {
        val text = state.text
        if (scope == null) {
            if (state.hasSelection) return removal(state.selectionStart, state.selectionEnd)
            // A bare "delete" deletes the word before the cursor, as Ctrl+Backspace does.
            val start = TextUnits.move(text, state.selectionStart, 1, TextUnit.WORD, forward = false)
            if (start == state.selectionStart) return EditStep.Fail(EditFailure.NOTHING_THERE)
            return tidied(text, start, state.selectionStart)
        }
        val range = when (val found = range(scope, state)) {
            is Missing -> return found.step
            is Found -> found.range
        }
        if (range.isEmpty()) return EditStep.Fail(EditFailure.NOTHING_THERE)
        val end = range.last + 1
        return when {
            scope is TextScope.Phrase || scope is TextScope.FromTo || scope is TextScope.Between -> tidied(text, range.first, end)
            scope is TextScope.Units && (scope.unit == TextUnit.WORD || scope.unit == TextUnit.SENTENCE) ->
                tidied(text, range.first, end)
            scope is TextScope.Units && (scope.unit == TextUnit.LINE || scope.unit == TextUnit.PARAGRAPH) ->
                lineTidied(text, range.first, end, paragraph = scope.unit == TextUnit.PARAGRAPH)
            else -> removal(range.first, end)
        }
    }

    private fun select(scope: TextScope, state: TextState): EditStep = when (val found = range(scope, state)) {
        is Missing -> found.step
        is Found -> EditStep.Select(found.range.first, found.range.last + 1)
    }

    private fun move(place: Place, state: TextState): EditStep = when (val found = offset(place, state)) {
        is Missing -> found.step
        is Found -> EditStep.Select(found.range.first, found.range.first)
    }

    /** Where [place] is in [state]'s text, as an empty range at that offset. */
    private fun offset(place: Place, state: TextState): Lookup {
        val text = state.text
        val cursor = state.selectionEnd
        val offset = when (place) {
            Place.Start -> 0
            Place.End -> text.length
            is Place.Before -> (TextSearch.find(text, place.phrase, cursor) ?: return notFound(place.phrase)).first
            is Place.After -> (TextSearch.find(text, place.phrase, cursor) ?: return notFound(place.phrase)).last + 1
            is Place.Between -> when (val both = pair(text, place.first, place.second, cursor)) {
                is PairLookup.Missing -> return Missing(both.step)
                is PairLookup.Both -> both.first.last + 1
            }
            is Place.Units -> {
                val from = if (place.forward) state.selectionEnd else state.selectionStart
                val to = TextUnits.move(text, from, place.count, place.unit, place.forward)
                if (to == from && !state.hasSelection) return Missing(EditStep.Fail(EditFailure.NOTHING_THERE))
                to
            }
        }
        return Found(offset until offset)
    }

    /** The text that [scope] names in [state]. */
    private fun range(scope: TextScope, state: TextState): Lookup {
        val text = state.text
        val cursor = state.selectionEnd
        val range = when (scope) {
            TextScope.Selection -> if (state.hasSelection) {
                state.selectionStart until state.selectionEnd
            } else {
                return Missing(EditStep.Fail(EditFailure.NOTHING_SELECTED))
            }
            TextScope.All -> text.indices
            TextScope.ToStart -> 0 until state.selectionEnd
            TextScope.ToEnd -> state.selectionStart until text.length
            is TextScope.Phrase -> TextSearch.find(text, scope.text, cursor) ?: return notFound(scope.text)
            is TextScope.FromTo -> when (val both = pair(text, scope.from, scope.to, cursor)) {
                is PairLookup.Missing -> return Missing(both.step)
                is PairLookup.Both -> both.first.first..both.second.last
            }
            is TextScope.Between -> when (val both = pair(text, scope.from, scope.to, cursor)) {
                is PairLookup.Missing -> return Missing(both.step)
                is PairLookup.Both -> trimmed(text, both.first.last + 1, both.second.first)
            }
            is TextScope.Units -> TextUnits.span(state, scope.count, scope.unit, scope.forward)
                ?: return Missing(EditStep.Fail(EditFailure.NOTHING_THERE))
        }
        return Found(range)
    }

    /**
     * [first] and [second] in the spoken order: of the pairs where a [first] comes before its nearest [second], with no
     * other [first] between them, the one nearest to the cursor. Only when the text has no such pair, [first] nearest to
     * the cursor with [second] nearest before it. Either way, in the order in which they stand in the text.
     */
    private fun pair(text: String, first: String, second: String, cursor: Int): PairLookup {
        val ones = TextSearch.all(text, first)
        if (ones.isEmpty()) return PairLookup.Missing(EditStep.Fail(EditFailure.NOT_FOUND, first))
        val others = TextSearch.all(text, second)
        val ordered = ones.mapNotNull { one ->
            val after = others.filter { it != one && it.first > one.last }.minByOrNull { it.first } ?: return@mapNotNull null
            val closer = ones.any { it.first > one.last && it.last < after.first }
            if (closer) null else PairLookup.Both(one, after)
        }
        val spans = ordered.map { it.first.first..it.second.last }
        TextSearch.nearest(spans, cursor)?.let { return ordered[spans.indexOf(it)] }
        val one = checkNotNull(TextSearch.nearest(ones, cursor))
        val before = others.filter { it != one && it.last < one.first }.maxByOrNull { it.last }
            ?: return PairLookup.Missing(EditStep.Fail(EditFailure.NOT_FOUND, second))
        return PairLookup.Both(before, one)
    }

    private fun insert(spoken: String, place: Place, state: TextState): EditStep {
        if (breaksLine(spoken)) return EditStep.Fail(EditFailure.LINE_BREAK)
        return when (val found = offset(place, state)) {
            is Missing -> found.step
            // The words go in as dictation at that place, with spoken punctuation and the spaces around them.
            is Found -> Dictation.render(TextState(state.text, found.range.first, found.range.first), spoken, startsUtterance = false)
        }
    }

    private fun replace(old: String, new: String, state: TextState): EditStep {
        if (breaksLine(new)) return EditStep.Fail(EditFailure.LINE_BREAK)
        val range = TextSearch.find(state.text, old, state.selectionEnd) ?: return EditStep.Fail(EditFailure.NOT_FOUND, old)
        val replacement = Dictation.phrase(new)
        val end = range.first + replacement.length
        return EditStep.Change(range.first, range.last + 1, replacement, end, end)
    }

    private fun replaceBetween(command: TextCommand.ReplaceBetween, state: TextState): EditStep {
        if (breaksLine(command.new)) return EditStep.Fail(EditFailure.LINE_BREAK)
        val text = state.text
        val both = when (val found = pair(text, command.from, command.to, state.selectionEnd)) {
            is PairLookup.Missing -> return found.step
            is PairLookup.Both -> found
        }
        val replacement = Dictation.phrase(command.new)
        val inner = trimmed(text, both.first.last + 1, both.second.first)
        if (inner.isEmpty()) {
            // Only spaces lie between the two: the new words go between them with a space on each side.
            val start = both.first.last + 1
            val end = start + 1 + replacement.length
            return EditStep.Change(start, both.second.first, " $replacement ", end, end)
        }
        val end = inner.first + replacement.length
        return EditStep.Change(inner.first, inner.last + 1, replacement, end, end)
    }

    private fun changeCase(case: TextCase, phrase: String, state: TextState): EditStep {
        val text = state.text
        val range = TextSearch.find(text, phrase, state.selectionEnd) ?: return EditStep.Fail(EditFailure.NOT_FOUND, phrase)
        val old = text.substring(range.first, range.last + 1)
        val new = when (case) {
            TextCase.UPPER -> old.uppercase(Locale.ROOT)
            TextCase.LOWER -> old.lowercase(Locale.ROOT)
            TextCase.CAPITALIZE -> capitalized(old)
        }
        val end = range.first + new.length
        return EditStep.Change(range.first, range.last + 1, new, end, end)
    }

    // "Format email" changes the selection, or the whole text when nothing is selected.
    private fun formatEmail(state: TextState): EditStep {
        val range = if (state.hasSelection) state.selectionStart until state.selectionEnd else state.text.indices
        if (range.isEmpty()) return EditStep.Fail(EditFailure.NOTHING_THERE)
        val email = Dictation.formatEmail(state.text.substring(range.first, range.last + 1))
        val end = range.first + email.length
        return EditStep.Change(range.first, range.last + 1, email, end, end)
    }

    /** The first letter of each word in capitals. */
    private fun capitalized(text: String): String = buildString {
        for ((index, char) in text.withIndex()) {
            val startsWord = index == 0 || !(text[index - 1].isLetterOrDigit() || text[index - 1] in APOSTROPHES)
            append(if (startsWord && char.isLetter()) char.titlecaseChar() else char)
        }
    }

    private fun removal(start: Int, end: Int) = EditStep.Change(start, end, "", start, start)

    /** Whether [spoken] holds a spoken "new line" or "new paragraph", which only the Enter key may type (design D8). */
    private fun breaksLine(spoken: String): Boolean = '\n' in Dictation.phrase(spoken)

    /**
     * Deletes [start] until [end] with one space next to it, so that no double space and no space before punctuation
     * stays behind: the space before it, or at the start of a line the space after it.
     */
    private fun tidied(text: String, start: Int, end: Int): EditStep.Change {
        val before = text.getOrNull(start - 1)
        val after = text.getOrNull(end)
        return when {
            before == ' ' && (after == null || after.isWhitespace() || after in CLOSING) -> removal(start - 1, end)
            (before == null || before == '\n') && after == ' ' -> removal(start, end + 1)
            else -> removal(start, end)
        }
    }

    /** Deletes whole lines or paragraphs with the line break, or the blank lines, that separated them from the rest. */
    private fun lineTidied(text: String, start: Int, end: Int, paragraph: Boolean): EditStep.Change {
        var from = start
        var to = end
        val endsLine = to == text.length || text[to] == '\n'
        val startsLine = from == 0 || text[from - 1] == '\n'
        if (endsLine && from > 0 && text[from - 1] == '\n') {
            from--
            if (paragraph) while (from > 0 && text[from - 1].isWhitespace()) from--
        } else if (startsLine && to < text.length && text[to] == '\n') {
            to++
            if (paragraph) while (to < text.length && text[to].isWhitespace()) to++
        }
        return removal(from, to)
    }

    /** [start] until [end] without the whitespace at both ends. */
    private fun trimmed(text: String, start: Int, end: Int): IntRange {
        var from = start
        var to = end
        while (from < to && text[from].isWhitespace()) from++
        while (to > from && text[to - 1].isWhitespace()) to--
        return from until to
    }

    private fun notFound(phrase: String) = Missing(EditStep.Fail(EditFailure.NOT_FOUND, phrase))

    private sealed interface Lookup

    private class Found(val range: IntRange) : Lookup

    private class Missing(val step: EditStep.Fail) : Lookup

    private sealed interface PairLookup {
        class Both(val first: IntRange, val second: IntRange) : PairLookup

        class Missing(val step: EditStep.Fail) : PairLookup
    }

    private val CLOSING = setOf('.', ',', '!', '?', ';', ':', ')', ']')
    private val APOSTROPHES = setOf('\'', '\u2019', '\u2018')
}
