package dev.kestrel.commands

import dev.kestrel.commands.TextCommand.Clipboard
import dev.kestrel.commands.TextCommand.Delete
import dev.kestrel.commands.TextCommand.Keyboard
import dev.kestrel.commands.TextCommand.MoveCursor
import dev.kestrel.commands.TextCommand.Select
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The text phrases of Appendix A (design spec, section 16.2). The first list is the text part of plan 3C's corpus,
 * exactly; none of the phrases is one that plan 3C's phone grammar takes.
 */
class GrammarTextTest {
    private fun units(count: Int, unit: TextUnit, forward: Boolean) = TextScope.Units(count, unit, forward)

    private val corpus: List<Pair<String, Command>> = listOf(
        "start editing" to TextCommand.StartEditing(null),
        "start editing the message" to TextCommand.StartEditing(Target.Label("the message")),
        "stop editing" to TextCommand.StopEditing,
        "stop typing" to TextCommand.StopEditing,
        "type hello there" to TextCommand.Type("hello there"),
        "type see you tomorrow" to TextCommand.Type("see you tomorrow"),
        "undo" to TextCommand.Undo,
        "undo that" to TextCommand.Undo,
        "redo" to TextCommand.Redo,
        "redo that" to TextCommand.Redo,
        "insert really before nice" to TextCommand.Insert("really", Place.Before("nice")),
        "insert very after really" to TextCommand.Insert("very", Place.After("really")),
        "insert and between salt and pepper" to TextCommand.Insert("and", Place.Between("salt", "pepper")),
        "insert too before late" to TextCommand.Insert("too", Place.Before("late")),
        "format email" to TextCommand.FormatEmail,
        "format as email address" to TextCommand.FormatEmail,
        "replace Monday with Tuesday" to TextCommand.Replace("Monday", "Tuesday"),
        "replace cat with dog" to TextCommand.Replace("cat", "dog"),
        "replace everything between hello and world with hi" to TextCommand.ReplaceBetween("hello", "world", "hi"),
        "replace everything between first and last with middle" to TextCommand.ReplaceBetween("first", "last", "middle"),
        "capitalize paris" to TextCommand.ChangeCase(TextCase.CAPITALIZE, "paris"),
        "capitalise london" to TextCommand.ChangeCase(TextCase.CAPITALIZE, "london"),
        "uppercase nasa" to TextCommand.ChangeCase(TextCase.UPPER, "nasa"),
        "uppercase hello" to TextCommand.ChangeCase(TextCase.UPPER, "hello"),
        "lowercase hello" to TextCommand.ChangeCase(TextCase.LOWER, "hello"),
        "lowercase world" to TextCommand.ChangeCase(TextCase.LOWER, "world"),
        "delete" to Delete(null),
        "delete that" to Delete(null),
        "delete all" to Delete(TextScope.All),
        "delete everything" to Delete(TextScope.All),
        "delete tomorrow" to Delete(TextScope.Phrase("tomorrow")),
        "delete the meeting" to Delete(TextScope.Phrase("the meeting")),
        "delete to the beginning" to Delete(TextScope.ToStart),
        "delete to the start" to Delete(TextScope.ToStart),
        "delete to the end" to Delete(TextScope.ToEnd),
        "delete to the end of the text" to Delete(TextScope.ToEnd),
        "delete selected text" to Delete(TextScope.Selection),
        "delete the selection" to Delete(TextScope.Selection),
        "delete from hello to world" to Delete(TextScope.FromTo("hello", "world")),
        "delete from first to last" to Delete(TextScope.FromTo("first", "last")),
        "delete three words" to Delete(units(3, TextUnit.WORD, false)),
        "delete the next two sentences" to Delete(units(2, TextUnit.SENTENCE, true)),
        "delete the last line" to Delete(units(1, TextUnit.LINE, false)),
        "delete two paragraphs" to Delete(units(2, TextUnit.PARAGRAPH, false)),
        "delete five characters" to Delete(units(5, TextUnit.CHARACTER, false)),
        "go to the beginning" to MoveCursor(Place.Start),
        "move to the start" to MoveCursor(Place.Start),
        "go to the end" to MoveCursor(Place.End),
        "move to the end" to MoveCursor(Place.End),
        "move after tomorrow" to MoveCursor(Place.After("tomorrow")),
        "move after the meeting" to MoveCursor(Place.After("the meeting")),
        "move before tomorrow" to MoveCursor(Place.Before("tomorrow")),
        "move before lunch" to MoveCursor(Place.Before("lunch")),
        "move between salt and pepper" to MoveCursor(Place.Between("salt", "pepper")),
        "move between hello and world" to MoveCursor(Place.Between("hello", "world")),
        "right two words" to MoveCursor(Place.Units(2, TextUnit.WORD, true)),
        "left three characters" to MoveCursor(Place.Units(3, TextUnit.CHARACTER, false)),
        "right one line" to MoveCursor(Place.Units(1, TextUnit.LINE, true)),
        "left one sentence" to MoveCursor(Place.Units(1, TextUnit.SENTENCE, false)),
        "right two paragraphs" to MoveCursor(Place.Units(2, TextUnit.PARAGRAPH, true)),
        "select all text" to Select(TextScope.All),
        "select all" to Select(TextScope.All),
        "unselect all text" to TextCommand.Unselect,
        "unselect" to TextCommand.Unselect,
        "select to the beginning" to Select(TextScope.ToStart),
        "select to the start" to Select(TextScope.ToStart),
        "select to the end" to Select(TextScope.ToEnd),
        "select to the end of the text" to Select(TextScope.ToEnd),
        "select tomorrow" to Select(TextScope.Phrase("tomorrow")),
        "select the meeting" to Select(TextScope.Phrase("the meeting")),
        "highlight meeting" to Select(TextScope.Phrase("meeting")),
        "highlight tomorrow" to Select(TextScope.Phrase("tomorrow")),
        "select from hello to world" to Select(TextScope.FromTo("hello", "world")),
        "select from first to last" to Select(TextScope.FromTo("first", "last")),
        "select three words" to Select(units(3, TextUnit.WORD, false)),
        "select the next two sentences" to Select(units(2, TextUnit.SENTENCE, true)),
        "copy" to Clipboard(ClipboardAction.COPY),
        "copy that" to Clipboard(ClipboardAction.COPY),
        "cut" to Clipboard(ClipboardAction.CUT),
        "cut that" to Clipboard(ClipboardAction.CUT),
        "paste" to Clipboard(ClipboardAction.PASTE),
        "paste that" to Clipboard(ClipboardAction.PASTE),
        "show keyboard" to Keyboard(show = true),
        "show the keyboard" to Keyboard(show = true),
        "hide keyboard" to Keyboard(show = false),
        "hide the keyboard" to Keyboard(show = false),
        "new line" to TextCommand.NewLine,
        "new line please" to TextCommand.NewLine,
        "show password" to TextCommand.ShowPassword,
        "show the password" to TextCommand.ShowPassword,
        "search for coffee near me" to TextCommand.Search("coffee near me"),
        "search for weather tomorrow" to TextCommand.Search("weather tomorrow"),
    )

    private val more: List<Pair<String, Command>> = listOf(
        "Type Hello, World." to TextCommand.Type("Hello, World"),
        "type see you now" to TextCommand.Type("see you now"),
        "Please type delete." to TextCommand.Type("delete"),
        "start editing 3" to TextCommand.StartEditing(Target.Number(3)),
        "stop dictating" to TextCommand.StopEditing,
        "undo it" to TextCommand.Undo,
        "insert hello at the end" to TextCommand.Insert("hello", Place.End),
        "insert Dear Ana at the beginning" to TextCommand.Insert("Dear Ana", Place.Start),
        "insert the cake before the tea before noon" to TextCommand.Insert("the cake before the tea", Place.Before("noon")),
        "replace with care with caution" to TextCommand.Replace("with care", "caution"),
        "replace between hello and world with hi" to TextCommand.ReplaceBetween("hello", "world", "hi"),
        "upper case the title" to TextCommand.ChangeCase(TextCase.UPPER, "the title"),
        "lower case NASA" to TextCommand.ChangeCase(TextCase.LOWER, "NASA"),
        "delete it" to Delete(null),
        "backspace" to Delete(null),
        "delete all the text" to Delete(TextScope.All),
        "delete the next word" to Delete(units(1, TextUnit.WORD, true)),
        "delete the previous word" to Delete(units(1, TextUnit.WORD, false)),
        "delete a word" to Delete(units(1, TextUnit.WORD, false)),
        "delete word" to Delete(units(1, TextUnit.WORD, false)),
        "delete the word" to Delete(TextScope.Phrase("the word")),
        "delete twenty one characters" to Delete(units(21, TextUnit.CHARACTER, false)),
        "delete between hello and world" to Delete(TextScope.Between("hello", "world")),
        "delete everything between hello and world" to Delete(TextScope.Between("hello", "world")),
        "move the cursor after lunch" to MoveCursor(Place.After("lunch")),
        "move cursor before lunch" to MoveCursor(Place.Before("lunch")),
        "go to start" to MoveCursor(Place.Start),
        "move the cursor to the end of the text" to MoveCursor(Place.End),
        "left a word" to MoveCursor(Place.Units(1, TextUnit.WORD, false)),
        "right 4 letters" to MoveCursor(Place.Units(4, TextUnit.CHARACTER, true)),
        "select everything" to Select(TextScope.All),
        "deselect" to TextCommand.Unselect,
        "select the previous three lines" to Select(units(3, TextUnit.LINE, false)),
        "select between hello and world" to Select(TextScope.Between("hello", "world")),
        "highlight from hello to world" to Select(TextScope.FromTo("hello", "world")),
        "Copy." to Clipboard(ClipboardAction.COPY),
        "paste it" to Clipboard(ClipboardAction.PASTE),
        "close the keyboard" to Keyboard(show = false),
        "newline" to TextCommand.NewLine,
        "reveal password" to TextCommand.ShowPassword,
        "format as email" to TextCommand.FormatEmail,
        // A lone "to" or "for" right after the verb is a number (design D5).
        "delete to words" to Delete(units(2, TextUnit.WORD, false)),
        "right to words" to MoveCursor(Place.Units(2, TextUnit.WORD, true)),
        "left for characters" to MoveCursor(Place.Units(4, TextUnit.CHARACTER, false)),
        // Polite words after a whole command go; a phrase or a payload keeps its words.
        "delete all please" to Delete(TextScope.All),
        "select all please" to Select(TextScope.All),
        "delete it now" to Delete(null),
        "start editing please" to TextCommand.StartEditing(null),
        "delete the last word please" to Delete(units(1, TextUnit.WORD, false)),
        "delete now" to Delete(TextScope.Phrase("now")),
        "delete please" to Delete(null),
    )

    @Test
    fun everyTextPhraseOfTheCorpusParsesToItsCommand() {
        for ((said, expected) in corpus) assertEquals(said, expected, CommandParser.parse(said))
    }

    @Test
    fun otherPhrasingsParseToo() {
        for ((said, expected) in more) assertEquals(said, expected, CommandParser.parse(said))
    }

    @Test
    fun writeIsRightOnlyBeforeANumberOfUnits() {
        // The recogniser writes "right" as "write" when no word comes before it.
        assertEquals(MoveCursor(Place.Units(2, TextUnit.WORD, true)), CommandParser.parse("Write two words."))
        assertEquals(MoveCursor(Place.Units(1, TextUnit.LINE, true)), CommandParser.parse("Write one line."))
        assertEquals(MoveCursor(Place.Units(2, TextUnit.PARAGRAPH, true)), CommandParser.parse("write 2 paragraphs"))
        for (said in listOf("Write a letter.", "write the next word", "write words", "write two letters to Ana", "write zero words", "write")) {
            val parsed = CommandParser.parse(said)
            assertTrue("$said gave $parsed", parsed is Command.Bare)
        }
    }

    @Test
    fun theDictationLinesOfTheCorpusAndHalfCommandsStayBare() {
        for (said in listOf(
            "hello comma how are you question mark",
            "see you at five period",
            "the meeting is on Monday exclamation mark",
            "capital p one two three",
            "open quote hello close quote she said",
            "type",
            "select",
            "highlight",
            "insert hello",
            "replace cat",
            "replace cat with",
            "left",
            "right now",
            "left three",
            "search",
            "search the web",
            "move along",
            "start the car",
            "upper deck",
            "go to the beach",
            "I think we should delete it later",
        )) {
            val parsed = CommandParser.parse(said)
            assertTrue("$said gave $parsed", parsed is Command.Bare)
        }
    }
}
