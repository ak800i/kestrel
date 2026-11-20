package dev.kestrel.voice.listen

import org.junit.Assert.assertEquals
import org.junit.Test

/** The microphone's level for the debug hooks (plan 4D, H7). */
class LevelMeterTest {
    @Test
    fun theLevelsAreTheRmsOfAllSamplesAndOfTheLoudestFrame() {
        val meter = LevelMeter()

        // A frame whose mean square is 0.25 (-6.0 dBFS), and a silent frame: 0.125 over both (-9.0 dBFS).
        meter.add(FloatArray(320) { if (it % 2 == 0) 0.5f else -0.5f })
        meter.add(FloatArray(320))

        assertEquals("rms=-9.0 frameRms=-6.0", meter.report())
    }

    @Test
    fun aReportStartsAgainAndSilenceHasAFloor() {
        val meter = LevelMeter()

        assertEquals("rms=none frameRms=none", meter.report())
        meter.add(FloatArray(320))
        assertEquals("rms=-120.0 frameRms=-120.0", meter.report())
        assertEquals("rms=none frameRms=none", meter.report())
    }
}
