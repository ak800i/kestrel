package dev.kestrel.app.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineTest {
    private var now = 10_000L
    private val timeline = Timeline { now }

    @Test
    fun aReactionCountsFromTheEndOfTheLatestSpeech() {
        timeline.reacted("mode-paused")
        assertEquals(Timeline.Mark("mode-paused", 10_000L), timeline.reacted)

        timeline.speechEnded(9_400L)
        // An unknown end, as from a pipeline without a clock, changes nothing.
        timeline.speechEnded(0L)
        now = 10_250L
        timeline.reacted("wait")

        assertEquals(Timeline.Mark("wait", 10_250L, sinceSpeech = 850L), timeline.reacted)
    }

    @Test
    fun theHooksAnswerHoldsOnlyTimesAndKinds() {
        assertEquals(
            "now=10000 started=none opened=0 speechEnded=0 reacted=none stopped=none settingsAsked=0 settingsResumed=0 " +
                "settingsInFront=false",
            timeline.describe(),
        )
        timeline.start("tile")
        now = 16_000L
        timeline.opened()
        timeline.speechEnded(17_000L)
        now = 17_600L
        timeline.reacted("wait")
        timeline.stopped("BATTERY_RESTRICTED")
        timeline.settingsAsked()
        now = 18_000L
        timeline.settingsResumed()
        assertTrue(timeline.settingsInFront)

        assertEquals(
            "now=18000 started=tile@10000 opened=16000 speechEnded=17000 reacted=wait@17600+600 " +
                "stopped=BATTERY_RESTRICTED@17600 settingsAsked=17600 settingsResumed=18000 settingsInFront=true",
            timeline.describe(),
        )
        timeline.settingsPaused()
        assertFalse(timeline.settingsInFront)
        assertEquals(18_000L, timeline.settingsResumes.value)
    }
}
