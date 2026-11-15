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
        // "cancelled" ends after 11 of the 22 characters, so at 1,600 ms.
        val spoken = listOf(Spoken("I cancelled the alarm.", startMillis = 1_000, endMillis = 2_200))

        assertTrue(filter.isOwnVoice(Keyword.CANCEL, hitMillis = 2_100, spoken = spoken))
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
