package dev.kestrel.app.listen

import dev.kestrel.voice.listen.Recognition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UtteranceStartsTest {
    private var now = 10_000L
    private val starts = UtteranceStarts { now }

    @Test
    fun aTranscriptGetsTheStartOfItsUtteranceBeforeTheDetectorReportedIt() {
        starts.speechStarted()
        now += 2_000
        // A report while the utterance goes on, such as one for the next utterance, keeps the first start.
        starts.speechStarted()

        assertEquals(9_700L, starts.ended())
        // Once a transcript has taken it, the next utterance needs a report of its own.
        assertNull(starts.ended())
        starts.speechStarted()
        assertEquals(11_700L, starts.ended())
    }

    @Test
    fun aStartWhoseUtteranceEndedWithoutATranscriptGoesToNoLaterTranscript() {
        // The utterance ends while Kestrel speaks, so neither a transcript nor any other event comes for it.
        starts.speechStarted()
        starts.recognitionChanged(Recognition.ALL, Recognition.NONE)
        now += 3_000
        starts.recognitionChanged(Recognition.NONE, Recognition.ALL)
        // The next utterance's report counts, not the start from before Kestrel spoke.
        starts.speechStarted()
        assertEquals(12_700L, starts.ended())

        // So when paused recognition skipped an utterance without the name, and when the microphone stopped in one.
        starts.speechStarted()
        starts.recognitionChanged(Recognition.NAMED, Recognition.ALL)
        assertNull(starts.ended())
        starts.speechStarted()
        starts.microphoneStarted()
        assertNull(starts.ended())

        // A change that skips no utterance keeps the start.
        starts.speechStarted()
        starts.recognitionChanged(Recognition.ALL, Recognition.ANSWER)
        assertEquals(12_700L, starts.ended())
    }
}
