package dev.kestrel.voice.sherpa

import org.junit.Assert.assertEquals
import org.junit.Test

class BoostWordsTest {
    @Test
    fun phrasesAreJoinedWithSlashes() {
        assertEquals("Viber/Zoran Petrović", BoostWords.format(listOf("Viber", "Zoran Petrović")))
    }

    @Test
    fun separatorsAndSymbolsBecomeSpaces() {
        assertEquals("a b/c d/e f", BoostWords.format(listOf("a/b", "c:d", "e 😀 f")))
    }

    @Test
    fun apostrophesHyphensAndDigitsStay() {
        assertEquals("O'Brien/Jean-Luc/R2-D2", BoostWords.format(listOf("O'Brien", "Jean-Luc", "R2-D2")))
    }

    @Test
    fun wordsWithoutALetterOrADigitAreDropped() {
        assertEquals("", BoostWords.format(listOf("-", "'", "- -")))
        assertEquals("Wi-Fi/Turn on", BoostWords.format(listOf("Wi-Fi", "Turn - on")))
    }

    @Test
    fun emptyLongAndRepeatedPhrasesAreDropped() {
        assertEquals("Viber", BoostWords.format(listOf(" ", "/", "x".repeat(41), "Viber", "VIBER")))
    }

    @Test
    fun atMostAHundredPhrasesAreKept() {
        assertEquals(100, BoostWords.format((1..150).map { "Contact $it" }).split('/').size)
    }
}
