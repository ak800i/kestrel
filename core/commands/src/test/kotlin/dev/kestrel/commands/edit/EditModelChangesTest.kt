package dev.kestrel.commands.edit

import dev.kestrel.commands.Place
import dev.kestrel.commands.TextCase
import dev.kestrel.commands.TextCommand
import dev.kestrel.commands.TextCommand.ChangeCase
import dev.kestrel.commands.TextCommand.Insert
import dev.kestrel.commands.TextCommand.Replace
import dev.kestrel.commands.TextCommand.ReplaceBetween
import org.junit.Assert.assertEquals
import org.junit.Test

/** Inserting, replacing, changing case and "format email" (design spec, section 9.7, and Appendix A). */
class EditModelChangesTest {
    private fun edit(command: TextCommand, marked: String): String = Marks.after(EditModel.plan(command, Marks.state(marked)), marked)

    @Test
    fun insertPutsTheWordsAtThePlaceWithSpacesAround() {
        assertEquals("a really |nice day", edit(Insert("really", Place.Before("nice")), "a nice day|"))
        assertEquals("a really very| nice", edit(Insert("very", Place.After("really")), "a really nice|"))
        assertEquals("salt and| pepper", edit(Insert("and", Place.Between("salt", "pepper")), "salt pepper|"))
        assertEquals("it is too |late", edit(Insert("too", Place.Before("late")), "it is late|"))
        assertEquals("Hello |world", edit(Insert("hello", Place.Start), "world|"))
        // Spoken punctuation works in the inserted words.
        assertEquals("yes,| I can", edit(Insert("comma", Place.After("yes")), "yes I can|"))
        assertEquals("hello. |world", edit(Insert("period", Place.Before("world")), "hello world|"))
        assertEquals("Fail NOT_FOUND", edit(Insert("very", Place.Before("moon")), "a nice day|"))
    }

    @Test
    fun replaceTakesTheNearestHitAndKeepsTheNewWordsAsSaid() {
        assertEquals("See you on Tuesday|.", edit(Replace("Monday", "Tuesday"), "See you on Monday.|"))
        assertEquals("cat and dog|", edit(Replace("cat", "dog"), "cat and cat|"))
        assertEquals("okay!|", edit(Replace("ok", "okay exclamation mark"), "ok|"))
        assertEquals("Fail NOT_FOUND", edit(Replace("moon", "sun"), "cat and cat|"))
    }

    @Test
    fun replaceEverythingBetweenKeepsBothEnds() {
        assertEquals("hello hi| world", edit(ReplaceBetween("hello", "world", "hi"), "hello big old world|"))
        assertEquals("hello hi| world", edit(ReplaceBetween("hello", "world", "hi"), "hello world|"))
    }

    @Test
    fun aSpokenLineBreakInTheNewWordsFails() {
        assertEquals("Fail LINE_BREAK", edit(Insert("new line", Place.Before("world")), "hello world|"))
        assertEquals("Fail LINE_BREAK", edit(Insert("thanks new paragraph", Place.End), "hello world|"))
        assertEquals("Fail LINE_BREAK", edit(Replace("hello", "hi new line there"), "hello world|"))
        assertEquals("Fail LINE_BREAK", edit(ReplaceBetween("hello", "world", "new line"), "hello big world|"))
    }

    @Test
    fun replaceTheArticleAndAWordReplacesOnlyTheWord() {
        assertEquals("Is that call| on", edit(Replace("the meeting", "call"), "Is that meeting on|"))
    }

    @Test
    fun capitalizeUppercaseAndLowercase() {
        assertEquals("i love Paris| and london", edit(ChangeCase(TextCase.CAPITALIZE, "paris"), "i love paris and london|"))
        assertEquals("in New York|", edit(ChangeCase(TextCase.CAPITALIZE, "new york"), "in new york|"))
        assertEquals("O'neil|", edit(ChangeCase(TextCase.CAPITALIZE, "oneil"), "o'neil|"))
        assertEquals("the NASA| team", edit(ChangeCase(TextCase.UPPER, "nasa"), "the nasa team|"))
        assertEquals("say hello|", edit(ChangeCase(TextCase.LOWER, "hello"), "say HELLO|"))
    }

    @Test
    fun capitalizeTreatsALeftSingleQuoteAsAnApostrophe() {
        assertEquals("O\u2018neil|", edit(ChangeCase(TextCase.CAPITALIZE, "oneil"), "o\u2018neil|"))
    }

    @Test
    fun capitalizeTreatsAQuoteAtTheStartOfAWordAsAnOpeningQuote() {
        assertEquals("Say \u2018Hello|", edit(ChangeCase(TextCase.CAPITALIZE, "say hello"), "say \u2018hello|"))
        assertEquals("Say 'Hello|", edit(ChangeCase(TextCase.CAPITALIZE, "say hello"), "say 'hello|"))
    }

    @Test
    fun formatEmailChangesTheSelectionOrElseTheWholeText() {
        assertEquals("john.smith@example.com|", edit(TextCommand.FormatEmail, "John dot Smith at example dot com|"))
        assertEquals("Mail: john@example.com|", edit(TextCommand.FormatEmail, "Mail: [john at example dot com]"))
        assertEquals("Fail NOTHING_THERE", edit(TextCommand.FormatEmail, "|"))
    }

    @Test
    fun commandsThatAreNoEditsArePlannedElsewhere() {
        assertEquals("Fail NOT_AN_EDIT", edit(TextCommand.Undo, "hello|"))
        assertEquals("Fail NOT_AN_EDIT", edit(TextCommand.Type("hi"), "hello|"))
    }
}
