package dev.kestrel.voice.sherpa

import dev.kestrel.voice.Transcript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TranscriptChoiceTest {
    private val whole = Transcript("Call Dragoljub on WhatsApp.", pieces = 9, lastWordEnd = 2.6f)
    private val cut = Transcript("Call Drago", pieces = 4, lastWordEnd = 0.9f)

    @Test
    fun aTranscriptThatReachesTheEndIsKeptWithoutASecondDecoding() {
        var decoded = 0

        assertSame(whole, TranscriptChoice.choose(2.7f, whole) { decoded++; cut })
        assertEquals(0, decoded)
    }

    @Test
    fun aTranscriptThatEndsEarlyGivesWayToALongerOne() {
        assertSame(whole, TranscriptChoice.choose(2.7f, cut) { whole })
    }

    @Test
    fun aTranscriptThatEndsEarlyStaysWhenTheSecondIsNotLonger() {
        assertSame(cut, TranscriptChoice.choose(2.7f, cut) { Transcript("Call", pieces = 1, lastWordEnd = 0.3f) })
    }

    @Test
    fun anEmptyTranscriptOfLongAudioIsDecodedAgain() {
        var decoded = 0

        TranscriptChoice.choose(2f, Transcript("", pieces = 0, lastWordEnd = 0f)) { decoded++; Transcript("", 0, 0f) }

        assertEquals(1, decoded)
    }
}
