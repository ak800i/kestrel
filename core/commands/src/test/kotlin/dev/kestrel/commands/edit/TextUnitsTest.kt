package dev.kestrel.commands.edit

import dev.kestrel.commands.TextUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
    fun aCharacterIsAGraphemeClusterAndAWordKeepsItsCombiningMarks() {
        // A thumb with a skin tone, a flag and a family: each is one character of several code points.
        for (cluster in listOf("\uD83D\uDC4D\uD83C\uDFFD", "\uD83C\uDDF7\uD83C\uDDF8", "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67")) {
            val text = "a${cluster}b"
            val end = 1 + cluster.length
            assertEquals(cluster.length.toString(), end, TextUnits.move(text, 1, 1, TextUnit.CHARACTER, forward = true))
            assertEquals(cluster.length.toString(), 1, TextUnits.move(text, end, 1, TextUnit.CHARACTER, forward = false))
        }
        // A decomposed "é" is one character, and the word keeps its accent.
        val decomposed = "cafe\u0301 ok"
        assertEquals(5, TextUnits.move(decomposed, 3, 1, TextUnit.CHARACTER, forward = true))
        assertEquals(3, TextUnits.move(decomposed, 5, 1, TextUnit.CHARACTER, forward = false))
        assertEquals(listOf(0..4, 6..7), TextUnits.segments(decomposed, TextUnit.WORD))
        assertEquals(5, TextUnits.move(decomposed, 0, 1, TextUnit.WORD, forward = true))
    }

    @Test
    fun theApostrophesAreTheNormalisersAndAnEmptyLineIsALine() {
        assertEquals(listOf(0..4, 6..9), TextUnits.segments("don\u2018t stop", TextUnit.WORD))
        assertEquals(listOf(0..4, 6..9), TextUnits.segments("don\u02BCt stop", TextUnit.WORD))
        // An empty line is an empty range, so that a line move stops on it.
        val lines = "first\n\nthird"
        assertEquals(listOf(0..4, 6 until 6, 7..11), TextUnits.segments(lines, TextUnit.LINE))
        assertEquals(6, TextUnits.move(lines, 5, 1, TextUnit.LINE, forward = true))
        assertEquals(6, TextUnits.move(lines, 7, 1, TextUnit.LINE, forward = false))
    }

    @Test(timeout = 1000)
    fun aHugeCountStopsAtTheTextsEndsAtOnce() {
        val emoji = "ab \uD83D\uDC4D\uD83C\uDFFD cd"
        // Without the cut to the characters that exist, each of these moves would take billions of steps.
        repeat(100) {
            assertEquals(emoji.length, TextUnits.move(emoji, 0, Int.MAX_VALUE, TextUnit.CHARACTER, forward = true))
            assertEquals(0, TextUnits.move(emoji, emoji.length, Int.MAX_VALUE, TextUnit.CHARACTER, forward = false))
        }
        val end = TextState(emoji, emoji.length, emoji.length)
        assertEquals(0 until emoji.length, TextUnits.span(end, 1_000_000, TextUnit.CHARACTER, forward = false))
        // Past the last word, a move goes on to the text's end.
        assertEquals(10, TextUnits.move("hi there  ", 0, Int.MAX_VALUE, TextUnit.WORD, forward = true))
        assertEquals(0, TextUnits.move("  hi there", 10, Int.MAX_VALUE, TextUnit.WORD, forward = false))
    }

    @Test
    fun aMoveNeedsACountOfAtLeastOne() {
        assertThrows(IllegalArgumentException::class.java) { TextUnits.move(text, 2, 0, TextUnit.WORD, forward = true) }
        assertThrows(IllegalArgumentException::class.java) { TextUnits.move(text, 2, 0, TextUnit.WORD, forward = false) }
        assertThrows(IllegalArgumentException::class.java) { TextUnits.move(text, 2, -1, TextUnit.CHARACTER, forward = true) }
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
