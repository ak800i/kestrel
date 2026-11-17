package dev.kestrel.commands.edit

import dev.kestrel.commands.TextUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextUnitsTest {
    private val text = "hello world foo"

    @Test
    fun aWordMoveEndsAtTheEdgeOfAWord() {
        assertEquals(5, TextUnits.move(text, 2, 1, TextUnit.WORD, forward = true))
        assertEquals(11, TextUnits.move(text, 6, 1, TextUnit.WORD, forward = true))
        assertEquals(15, TextUnits.move(text, 11, 1, TextUnit.WORD, forward = true))
        assertEquals(0, TextUnits.move(text, 6, 1, TextUnit.WORD, forward = false))
        assertEquals(6, TextUnits.move(text, 11, 1, TextUnit.WORD, forward = false))
        // A count beyond the text stops at its ends.
        assertEquals(0, TextUnits.move(text, 15, 5, TextUnit.WORD, forward = false))
        assertEquals(15, TextUnits.move(text, 0, 9, TextUnit.WORD, forward = true))
    }

    @Test
    fun aWordKeepsItsApostropheAndACharacterIsAWholeCharacter() {
        assertEquals(listOf(0..4, 6..9), TextUnits.segments("don't stop", TextUnit.WORD))
        val emoji = "a\uD83D\uDE00b"
        assertEquals(3, TextUnits.move(emoji, 1, 1, TextUnit.CHARACTER, forward = true))
        assertEquals(1, TextUnits.move(emoji, 3, 1, TextUnit.CHARACTER, forward = false))
        assertEquals(0, TextUnits.move("ab", 1, 5, TextUnit.CHARACTER, forward = false))
    }

    @Test
    fun aSentenceEndsWithItsMarkBeforeWhitespaceOrTheEnd() {
        val text = "One. Two? Three! It costs 1.5 euro. \"Done.\" Last"

        assertEquals(listOf(0..3, 5..8, 10..15, 17..34, 36..42, 44..47), TextUnits.segments(text, TextUnit.SENTENCE))
    }

    @Test
    fun aLineEndsAtANewlineAndAParagraphAtABlankLine() {
        val lines = "first line\nsecond\nthird"
        assertEquals(10, TextUnits.move(lines, 3, 1, TextUnit.LINE, forward = true))
        assertEquals(17, TextUnits.move(lines, 10, 1, TextUnit.LINE, forward = true))
        assertEquals(11, TextUnits.move(lines, 13, 1, TextUnit.LINE, forward = false))
        assertEquals(0, TextUnits.move(lines, 11, 1, TextUnit.LINE, forward = false))
        assertEquals(11, TextUnits.move(lines, 23, 2, TextUnit.LINE, forward = false))

        val paragraphs = "Para one.\n\nPara two line one\nline two\n\n\nPara three"
        assertEquals(listOf(0..8, 11..36, 40..49), TextUnits.segments(paragraphs, TextUnit.PARAGRAPH))
        // Without a blank line, the whole text is one paragraph.
        assertEquals(listOf(0..2), TextUnits.segments("a\nb", TextUnit.PARAGRAPH))
    }

    @Test
    fun aSpanGoesBackwardsFromTheSelectionsStartAndForwardsFromItsEnd() {
        assertEquals(6..14, TextUnits.span(TextState(text, 15, 15), 2, TextUnit.WORD, forward = false))
        assertEquals(0..4, TextUnits.span(TextState(text, 0, 0), 1, TextUnit.WORD, forward = true))
        assertEquals(11..14, TextUnits.span(TextState(text, 6, 11), 1, TextUnit.WORD, forward = true))
        assertNull(TextUnits.span(TextState(text, 15, 15), 1, TextUnit.WORD, forward = true))
    }
}
