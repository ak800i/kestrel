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
    fun splitsALongSentenceAfterAComma() {
        val first = "a".repeat(150) + ","
        val second = "b".repeat(100)

        assertEquals(listOf(first, second), Sentences.split("$first $second"))
    }

    @Test
    fun splitsALongSentenceWithoutPunctuationAtSpaces() {
        val pieces = Sentences.split(List(60) { "word" }.joinToString(" "))

        assertEquals(2, pieces.size)
        assertTrue(pieces.all { it.length <= Sentences.MAX_LENGTH })
        assertEquals(List(60) { "word" }, pieces.flatMap { it.split(" ") })
    }

    @Test
    fun emptyTextHasNoSentences() {
        assertEquals(emptyList<String>(), Sentences.split("  \n "))
    }
}
