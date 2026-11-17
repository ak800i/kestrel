package dev.kestrel.commands.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NormalizerTest {
    @Test
    fun lowercasesAndTurnsPunctuationIntoSingleSpaces() {
        assertEquals("tap send now", Normalizer.normalize("  Tap SEND, now!  "))
        assertEquals(listOf("scroll", "down"), Normalizer.words("Scroll\tdown."))
        assertEquals(emptyList<String>(), Normalizer.words(" ?! "))
    }

    @Test
    fun deletesApostrophesAsRequestsDoes() {
        assertEquals("dont stop", Normalizer.normalize("Don't stop"))
        assertEquals("dont stop", Normalizer.normalize("Don\u2019t stop"))
    }

    @Test
    fun everyDashAndTheNonBreakingHyphenSeparateWords() {
        for (dash in listOf("-", "\u2010", "\u2011", "\u2012", "\u2013", "\u2014")) {
            assertEquals("wi fi", Normalizer.normalize("Wi${dash}Fi"))
        }
    }

    @Test
    fun foldsDiacriticsWithTheExplicitRules() {
        assertEquals("cevapcici sargarepa zaba", Normalizer.normalize("Ćevapčići šargarepa žaba"))
        assertEquals("djordje", Normalizer.normalize("Đorđe"))
        assertEquals("strasse lodz aero oeuvre", Normalizer.normalize("Straße Łódź Ærø Œuvre"))
        assertEquals("cafe", Normalizer.normalize("Cafe\u0301"))
    }

    @Test
    fun transliteratesSerbianRussianAndUkrainianCyrillic() {
        assertEquals("djordje", Normalizer.normalize("Ђорђе"))
        assertEquals("ana petrovic", Normalizer.normalize("Ана Петровић"))
        assertEquals("ljubav njiva dzep", Normalizer.normalize("Љубав њива џеп"))
        assertEquals("scuka julja jasa", Normalizer.normalize("щука, Юля, Яша"))
        assertEquals("obekt", Normalizer.normalize("объект"))
        assertEquals("jevropa jizak", Normalizer.normalize("Європа їжак"))
        // Decomposed (NFD) letters fold as their composed forms: "и" with a breve is "й", "і" with a diaeresis is "ї".
        assertEquals("jovan jizak cafe", Normalizer.normalize("И\u0306ован і\u0308жак Cafe\u0301"))
    }

    @Test
    fun withOffsetsMapsEachFoldedCharacterToTheOriginalText() {
        val original = "Tap «Ђорђе», please"
        val folded = Normalizer.withOffsets(original)

        assertEquals("tap djordje please", folded.text)
        assertEquals(listOf(0..2, 4..10, 12..17), folded.words)
        // The two folded letters "dj" both come from "Ђ".
        assertEquals(5..5, folded.source(4, 6))
        assertEquals("Ђорђе", original.substring(folded.span(1, 2)))
        assertEquals("Ђорђе», please", original.substring(folded.span(1, 3)))
        assertFalse(folded.toString().contains("djordje"))
        // An empty range is empty in the original text too: where its first folded character or word starts, or after
        // the last one. Kotlin counts every two empty ranges as equal, so the checks compare the positions.
        assertEmptyAt(5, folded.source(4, 4))
        assertEmptyAt(5, folded.span(1, 1))
        assertEmptyAt(0, folded.span(0, 0))
        assertEmptyAt(19, folded.span(3, 3))
        assertEmptyAt(19, folded.source(18, 18))
        assertEquals("", original.substring(folded.span(3, 3)))
        assertEmptyAt(0, Normalizer.withOffsets(" ?! ").span(0, 0))
    }

    private fun assertEmptyAt(position: Int, range: IntRange) {
        assertEquals(position, range.first)
        assertEquals(position - 1, range.last)
    }

    @Test
    fun okayOkAndOKWithDotsAreTheSameWord() {
        assertEquals("ok tap ok", Normalizer.normalize("Okay, tap OK"))
        assertEquals("ok", Normalizer.normalize("O.K."))
        val original = "Okay, tap O.K."
        val folded = Normalizer.withOffsets(original)
        assertEquals("ok tap ok", folded.text)
        assertEquals("Okay", original.substring(folded.span(0, 1)))
        assertEquals("O.K", original.substring(folded.span(2, 3)))
        // Two letters with a space between them stay two words.
        assertEquals(listOf("o", "k"), Normalizer.words("O K"))
    }

    @Test
    fun withOffsetsKeepsTheRecognisersCaseAndInnerPunctuation() {
        val original = "type Hello, World!"
        val folded = Normalizer.withOffsets(original)

        assertEquals("Hello, World", original.substring(folded.span(1, 3)))
        // The combining marks after a letter belong to its word.
        val decomposed = "type Cafe\u0301 au lait"
        assertEquals("Cafe\u0301", decomposed.substring(Normalizer.withOffsets(decomposed).span(1, 2)))
        val cyrillic = "Ми\u0306 і\u0308"
        assertEquals("Ми\u0306", cyrillic.substring(Normalizer.withOffsets(cyrillic).span(0, 1)))
        assertEquals("і\u0308", cyrillic.substring(Normalizer.withOffsets(cyrillic).span(1, 2)))
    }
}
