package dev.kestrel.commands.edit

import org.junit.Assert.assertEquals
import org.junit.Test

/** Dictation and spelling (design spec, section 9.7, and Appendix A: typing and punctuation). */
class DictationTest {
    /** What dictating [spoken] at [marked] leaves, with the parts joined at their line breaks. */
    private fun dictate(marked: String, spoken: String, password: Boolean = false): String {
        val state = Marks.state(marked)
        var text = state.text
        var cursor = state.selectionStart
        for (part in Dictation.insert(state, spoken, password)) {
            when (part) {
                is DictationPart.Text -> {
                    text = part.change.applyTo(text)
                    cursor = part.change.selectionEnd
                }
                DictationPart.NewLine -> {
                    text = text.substring(0, cursor) + "\n" + text.substring(cursor)
                    cursor++
                }
            }
        }
        return Marks.mark(text, cursor, cursor)
    }

    @Test
    fun theDictationLinesOfTheCorpusIntoAnEmptyField() {
        val lines = listOf(
            "hello comma how are you question mark" to "Hello, how are you?",
            "see you at five period" to "See you at five.",
            "the meeting is on Monday exclamation mark" to "The meeting is on Monday!",
            "capital p one two three" to "P123",
            "open quote hello close quote she said" to "\"Hello\" she said",
        )

        for ((spoken, expected) in lines) {
            val parts = Dictation.insert(TextState("", 0, 0), spoken, password = false)
            assertEquals(spoken, listOf(DictationPart.Text(EditStep.Change(0, 0, expected, expected.length, expected.length))), parts)
        }
    }

    @Test
    fun theRecognisersPunctuationGoesAndTheFirstWordLosesItsCapital() {
        assertEquals("We met yesterday|", dictate("We met |", "Yesterday."))
        assertEquals("Well, I think so|", dictate("Well, |", "I think so."))
        assertEquals("Well, NASA called|", dictate("Well, |", "NASA called."))
        assertEquals("Yes, I'm here|", dictate("Yes, |", "I'm here."))
        assertEquals("OK|", dictate("|", "OK."))
        assertEquals("It costs 3.5 euro at 10:30|", dictate("It costs |", "3.5 euro at 10:30."))
    }

    @Test
    fun aCapitalComesAfterASentenceEndInTheFieldOrSaidNow() {
        assertEquals("Done. See you|", dictate("Done.|", "see you"))
        assertEquals("Done. See you|", dictate("Done. |", "see you"))
        assertEquals("Done. \"Hello|", dictate("Done. \"|", "hello"))
        assertEquals("He said \"hello|", dictate("He said \"|", "hello"))
        assertEquals("How are you? Fine thanks|", dictate("|", "how are you question mark fine thanks"))
        assertEquals("ok so|", dictate("ok|", "so"))
    }

    @Test
    fun spacesGoBetweenWordsAndNotBeforeClosingMarks() {
        assertEquals("hello world|", dictate("hello|", "world"))
        assertEquals("hello world|", dictate("hello |", "world"))
        assertEquals("hello,|", dictate("hello|", "comma"))
        assertEquals("hello, world|", dictate("hello,|", "world"))
        // Before a word of the field, a space follows the new words.
        assertEquals("Hello |world", dictate("|world", "hello"))
        assertEquals("Ring me (maybe)|", dictate("|", "ring me open parenthesis maybe close parenthesis"))
        assertEquals("My_name@mail|", dictate("|", "my underscore name at sign mail"))
        // A closing mark takes the place of the space before the cursor, and "dot" joins without starting a sentence.
        assertEquals("hello.|", dictate("hello |", "period"))
        assertEquals("hello.|", dictate("hello\u00A0|", "period"))
        assertEquals("John.smith at example.com|", dictate("|", "john dot smith at example dot com"))
    }

    @Test
    fun doubleQuotesOpenAndCloseByHowManyComeBefore() {
        assertEquals("\"Hi\"|", dictate("|", "quote hi quote"))
        assertEquals("He said \"hi\"|", dictate("He said \"hi|", "quote"))
    }

    @Test
    fun aNewLineSplitsTheParts() {
        assertEquals(
            listOf(
                DictationPart.Text(EditStep.Change(0, 0, "Hello", 5, 5)),
                DictationPart.NewLine,
                DictationPart.Text(EditStep.Change(6, 6, "world", 11, 11)),
            ),
            Dictation.insert(TextState("", 0, 0), "hello new line world", password = false),
        )
        assertEquals(
            listOf(DictationPart.NewLine, DictationPart.NewLine, DictationPart.Text(EditStep.Change(7, 7, "Thanks", 13, 13))),
            Dictation.insert(TextState("Done.", 5, 5), "new paragraph thanks", password = false),
        )
        assertEquals("Hi.\nThere|", dictate("Hi.|", "new line there"))
    }

    @Test
    fun theSelectionIsReplaced() {
        assertEquals("hello there|", dictate("hello [world]", "there"))
    }

    @Test
    fun spelledWordsAreJoined() {
        assertEquals("Code: abc|", dictate("Code: |", "a b c"))
        assertEquals("Code: X2|", dictate("Code: |", "capital x 2"))
        assertEquals("Pin 123|", dictate("Pin|", "one two three"))
    }

    @Test
    fun inAPasswordFieldEveryWordIsSpelled() {
        assertEquals("hunter2b|", dictate("|", "Hunter two bee.", password = true))
        assertEquals("hunter2!|", dictate("|", "hunter two exclamation mark", password = true))
        assertEquals("P@ss_1|", dictate("|", "capital p at sign s s underscore 1", password = true))
        assertEquals("a#1$+=*|", dictate("|", "a hash one dollar sign plus sign equals sign asterisk", password = true))
        assertEquals("Pw9|", dictate("|", "capital p double u 9", password = true))
        assertEquals("ab|", dictate("a|", "bee", password = true))
    }

    @Test
    fun formatEmailAndPhrases() {
        assertEquals("john.smith@example-shop.com", Dictation.formatEmail("John Dot Smith AT example dash shop dot com"))
        assertEquals("Tuesday,", Dictation.phrase("Tuesday comma"))
        assertEquals("next week", Dictation.phrase("next week."))
    }
}
