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
    fun aLatinReadingOfOnlySpacesCountsAsNone() {
        // The Latin recogniser reads Cyrillic text as spaces with a score of about 0.99.
        assertEquals(cyrillic to true, choose(Reading("  ", 0.99)))
        assertEquals(Reading("", 0.0), OcrEngine.choose(Reading(" ", 0.99), false) { Reading(" ", 0.98) })
    }
}
