package dev.kestrel.voice.speak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentencesTest {
    @Test
    fun splitsAtTheEndOfEachSentence() {
        assertEquals(
            listOf("Done.", "I sent the message!", "Anything else?"),
            Sentences.split("Done. I sent the message!  Anything else?"),
        )
    }

    @Test
    fun keepsNumbersTogether() {
        assertEquals(listOf("It costs 3.50 euros."), Sentences.split("It costs 3.50 euros."))
    }

    @Test
    fun splitsAtLineBreaks() {
        assertEquals(listOf("First line", "Second line"), Sentences.split("First line\n\nSecond line\n"))
    }

    @Test
    fun keepsAbbreviationsInsideASentence() {
        assertEquals(
            listOf("I will call you at 7 a.m. tomorrow.", "Pick a fruit, e.g. an apple."),
            Sentences.split("I will call you at 7 a.m. tomorrow. Pick a fruit, e.g. an apple."),
        )
    }

    @Test
    fun splitsAfterAClosingQuoteOrBracket() {
        assertEquals(
            listOf("He said \"Stop.\"", "She said “Done.”", "Tap Send (the blue button.)", "Then wait."),
            Sentences.split("He said \"Stop.\" She said “Done.” Tap Send (the blue button.) Then wait."),
        )
    }

    @Test
    fun splitsAtANoBreakSpace() {
        assertEquals(listOf("Done.", "Next one."), Sentences.split("Done.\u00A0Next one."))
    }

    @Test
    fun splitsALongSentenceAfterAComma() {
        val first = List(20) { "word" }.joinToString(" ") + ","
        val second = List(30) { "word" }.joinToString(" ")

        assertEquals(listOf(first, second), Sentences.split("$first $second"))
    }

    @Test
    fun keepsTimesAndNumbersTogetherInALongSentence() {
        fun more(count: Int) = List(count) { "more" }.joinToString(" ")

        assertEquals(
            listOf("We meet at 10:15 and it costs 1,250 euros ${more(31)}", more(9)),
            Sentences.split("We meet at 10:15 and it costs 1,250 euros ${more(40)}"),
        )
    }

    @Test
    fun splitsALongSentenceWithoutPunctuationAtSpaces() {
        val pieces = Sentences.split(List(50) { "words" }.joinToString(" "))

        assertEquals(2, pieces.size)
        assertTrue(pieces.all { it.length <= Sentences.MAX_LENGTH })
        assertEquals(List(50) { "words" }, pieces.flatMap { it.split(" ") })
    }

    @Test
    fun doesNotCutASurrogatePairInHalf() {
        val emoji = "\uD83D\uDE00"

        assertEquals(listOf("x" + emoji.repeat(99), emoji.repeat(31)), Sentences.split("x" + emoji.repeat(130)))
    }

    @Test
    fun emptyTextHasNoSentences() {
        assertEquals(emptyList<String>(), Sentences.split("  \n "))
    }
}
