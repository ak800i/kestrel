package dev.kestrel.voice.sherpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoostWordsTest {
    // Pieces like those of Parakeet's tokens.txt: the word-start marker, and the Latin letters, the digits, the
    // apostrophe, the hyphen and "ć", each after the marker.
    private val spelling = BoostWords.characters(
        "<unk> 0\n▁ 1\n" + "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'-ć".withIndex()
            .joinToString("") { (id, character) -> "▁$character ${id + 2}\n" },
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
    fun aCharacterThatAppearsOnlyInsideALongerPieceCannotBeSpelled() {
        // "b", "e" and "r" appear only in "ber", and the characters of the special token only in "<unk>".
        val spelling = BoostWords.characters("<unk> 0\n▁ 1\n▁V 2\ni 3\nber 4\n")

        assertEquals(2, spelling.size)
        assertTrue('V' in spelling && 'i' in spelling)
        assertEquals(0, BoostWords.format(listOf("Viber"), spelling).length)
    }

    @Test
    fun withoutTheWordStartMarkerAsAPieceOfItsOwnEveryPhraseIsDropped() {
        // The encoder could not start a word.
        val spelling = BoostWords.characters("<unk> 0\n▁K 1\ne 2\ns 3\nt 4\nr 5\nl 6\n")

        assertEquals(0, BoostWords.format(listOf("Kestrel", "Kestrel Kestrel"), spelling).length)
    }

    @Test
    fun aPhraseWhoseCharactersArePiecesOfTheirOwnIsKept() {
        // As in Parakeet's tokens.txt, a character is a piece with or without the marker, among longer pieces.
        val spelling = BoostWords.characters("<unk> 0\n▁ 1\n▁K 2\ne 3\ns 4\n▁t 5\nr 6\nl 7\nrel 8\n")

        // Neither the start of a phrase nor the space between two words needs a piece with the marker.
        val formatted = BoostWords.format(listOf("Kestrel", "Kestrel Kestrel"), spelling)
        assertTrue("${formatted.length} characters", formatted == "Kestrel/Kestrel Kestrel")
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
