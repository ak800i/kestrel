package dev.kestrel.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The choice between the Latin and the Cyrillic recogniser for one box (design D20 of sub-project 5; r48, Fit 4). */
class RecogniserChoiceTest {
    private val cyrillic = Reading("Ђорђе", 0.95)

    private fun choose(latin: Reading, nearby: Boolean = false): Pair<Reading, Boolean> {
        var asked = false
        val chosen = OcrEngine.choose(latin, nearby) { asked = true; cyrillic }
        return chosen to asked
    }

    @Test
    fun aSureLatinReadingIsKeptWithoutTheCyrillicRecogniser() {
        val latin = Reading("Settings", 0.99)

        assertEquals(latin to false, choose(latin))
    }

    @Test
    fun anUnsureLatinReadingOrCyrillicTextNearbyLetsTheHigherScoreWin() {
        assertEquals(cyrillic to true, choose(Reading("Bopbe", 0.6)))
        assertEquals(cyrillic to true, choose(Reading("Bopbe", 0.9), nearby = true))
        val sure = Reading("Pay now", 0.99)
        assertSame(sure, choose(sure, nearby = true).first)
    }

    @Test
    fun withCyrillicNearbyASureLatinReadingGivesWayOnlyToAReadingWithCyrillicLetters() {
        // The Cyrillic dictionary lacks č, ć, š, ž and đ, so it reads Serbian Latin text as plain ASCII with a high score.
        val serbian = Reading("Đorđe šalje poruku", 0.983)
        assertSame(serbian, OcrEngine.choose(serbian, true) { Reading("Dorde salje poruku", 0.995) })
        val city = Reading("Нови Сад", 0.97)
        assertSame(city, OcrEngine.choose(Reading("Hobn Cag", 0.85), true) { city })
        // Below 0.8, the higher score wins whatever its letters.
        val plain = Reading("Dorde", 0.9)
        assertSame(plain, OcrEngine.choose(Reading("Dorde", 0.6), true) { plain })
    }

    @Test
    fun aLatinReadingOfOnlySpacesCountsAsNone() {
        // The Latin recogniser reads Cyrillic text as spaces with a score of about 0.99.
        assertEquals(cyrillic to true, choose(Reading("  ", 0.99)))
        assertEquals(Reading("", 0.0), OcrEngine.choose(Reading(" ", 0.99), false) { Reading(" ", 0.98) })
    }
}
