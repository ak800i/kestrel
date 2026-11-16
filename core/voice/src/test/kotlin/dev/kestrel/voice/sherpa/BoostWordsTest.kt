package dev.kestrel.voice.sherpa

import org.junit.Assert.assertEquals
import org.junit.Test

class BoostWordsTest {
    // Pieces like those of Parakeet's tokens.txt: the Latin letters, the digits, the apostrophe, the hyphen and "ć".
    private val spelling = BoostWords.characters(
        "<unk> 0\n" + "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'-ć".withIndex()
            .joinToString("") { (id, character) -> "▁$character ${id + 1}\n" },
    )

    @Test
    fun aPhraseWithACharacterThatTheModelCannotSpellIsDropped() {
        assertEquals(
            "Viber/Zoran Petrović",
            BoostWords.format(listOf("日本語", "Viber", "Вибер", "Zoran Petrović", "Zoran Petrovič"), spelling),
        )
        // A dropped phrase does not count towards the cap.
        assertEquals("Viber", BoostWords.format((1..100).map { "Вибер $it" } + "Viber", spelling))
    }

    @Test
    fun theModelSpellsTheCharactersOfItsPiecesAndNeedsNoWordStartMarker() {
        val spelling = BoostWords.characters("<unk> 0\n▁Vi 1\nber 2\n▁ 3\n")

        assertEquals("<unk>Viber".toSet(), spelling)
        // Neither the start of a phrase nor the space between two words needs the marker.
        assertEquals("Viber Vibe", BoostWords.format(listOf("Viber Vibe"), spelling))
    }

    @Test
    fun phrasesAreJoinedWithSlashes() {
        assertEquals("Viber/Zoran Petrović", BoostWords.format(listOf("Viber", "Zoran Petrović"), spelling))
    }

    @Test
    fun separatorsAndSymbolsBecomeSpaces() {
        assertEquals("a b/c d/e f", BoostWords.format(listOf("a/b", "c:d", "e 😀 f"), spelling))
    }

    @Test
    fun apostrophesHyphensAndDigitsStay() {
        assertEquals("O'Brien/Jean-Luc/R2-D2", BoostWords.format(listOf("O'Brien", "Jean-Luc", "R2-D2"), spelling))
    }

    @Test
    fun wordsWithoutALetterOrADigitAreDropped() {
        assertEquals("", BoostWords.format(listOf("-", "'", "- -"), spelling))
        assertEquals("Wi-Fi/Turn on", BoostWords.format(listOf("Wi-Fi", "Turn - on"), spelling))
    }

    @Test
    fun emptyLongAndRepeatedPhrasesAreDropped() {
        assertEquals("Viber", BoostWords.format(listOf(" ", "/", "x".repeat(41), "Viber", "VIBER"), spelling))
    }

    @Test
    fun atMostAHundredPhrasesAreKept() {
        assertEquals(100, BoostWords.format((1..150).map { "Contact $it" }, spelling).split('/').size)
    }
}
