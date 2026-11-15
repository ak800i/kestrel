package dev.kestrel.voice.speak

import dev.kestrel.voice.Keyword
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoFilterTest {
    private val filter = EchoFilter()

    // "Stop" ends after 4 of the 15 characters, so at about 1,266 ms.
    private val question = Spoken("Stop the timer?", startMillis = 1_000, endMillis = 2_000)

    @Test
    fun aKeywordThatKestrelJustSaidIsItsOwnVoice() {
        assertTrue(filter.isOwnVoice(Keyword.STOP, hitMillis = 1_600, spoken = listOf(question)))
    }

    @Test
    fun theSameKeywordLaterIsTheOwners() {
        assertFalse(filter.isOwnVoice(Keyword.STOP, hitMillis = 4_000, spoken = listOf(question)))
    }

    @Test
    fun aKeywordThatKestrelDidNotSayIsTheOwners() {
        assertFalse(filter.isOwnVoice(Keyword.CANCEL, hitMillis = 1_600, spoken = listOf(question)))
    }

    @Test
    fun aWordThatStartsWithTheKeywordCounts() {
        // "cancel" in "cancelled" ends after 8 of the 22 characters, so at about 1,436 ms.
        val spoken = listOf(Spoken("I cancelled the alarm.", startMillis = 1_000, endMillis = 2_200))

        assertTrue(filter.isOwnVoice(Keyword.CANCEL, hitMillis = 2_100, spoken = spoken))
    }

    @Test
    fun aKeywordInsideAWordCounts() {
        // "stop" in "nonstop" ends after 15 of the 16 characters, so at 1,500 ms.
        val spoken = listOf(Spoken("It runs nonstop.", startMillis = 0, endMillis = 1_600))

        assertTrue(filter.isOwnVoice(Keyword.STOP, hitMillis = 1_900, spoken = spoken))
    }

    @Test
    fun aKeywordAcrossWordsCounts() {
        // "stop" in "It's top" ends after 8 of the 15 characters, so at 800 ms.
        val spoken = listOf(Spoken("It's top-rated.", startMillis = 0, endMillis = 1_500))

        assertTrue(filter.isOwnVoice(Keyword.STOP, hitMillis = 1_200, spoken = spoken))
    }

    @Test
    fun aDigitTakesAsLongAsThreeCharacters() {
        // Piper says the digits as words, so "cancel" ends after 25 of the 32 weighted characters, at 2,343 ms, and not
        // after 17 of the 24 characters, at 2,125 ms.
        val spoken = listOf(Spoken("At 06:45 I cancelled it.", startMillis = 0, endMillis = 3_000))

        assertTrue(filter.isOwnVoice(Keyword.CANCEL, hitMillis = 3_800, spoken = spoken))
        assertFalse(filter.isOwnVoice(Keyword.CANCEL, hitMillis = 1_300, spoken = spoken))
    }

    @Test
    fun aHitUpToOneSecondBeforeTheEstimatedEndIsItsOwnVoice() {
        // "Stop" is estimated to end at about 1,266 ms.
        assertTrue(filter.isOwnVoice(Keyword.STOP, hitMillis = 300, spoken = listOf(question)))
        assertFalse(filter.isOwnVoice(Keyword.STOP, hitMillis = 200, spoken = listOf(question)))
    }

    @Test
    fun aHitWhileTheNextSentencePlaysMatchesTheSentenceBefore() {
        // "stop" ends at about 928 ms, and the hit comes while the next sentence plays.
        val spoken = listOf(Spoken("Should I stop?", 0, 1_000), Spoken("The music is loud.", 1_100, 2_600))

        assertTrue(filter.isOwnVoice(Keyword.STOP, hitMillis = 1_400, spoken = spoken))
    }

    @Test
    fun capitalsDoNotMatter() {
        assertTrue(filter.isOwnVoice(Keyword.KESTREL, hitMillis = 1_000, spoken = listOf(Spoken("KESTREL here.", 0, 1_000))))
    }

    @Test
    fun withNothingSpokenEveryHitIsTheOwners() {
        assertFalse(filter.isOwnVoice(Keyword.STOP, hitMillis = 1_600, spoken = emptyList()))
    }
}
