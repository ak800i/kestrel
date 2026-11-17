package dev.kestrel.app.listen

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
}
