package dev.kestrel.app.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/** The resampler of the generated speech: Piper speaks at 22.05 kHz and Kokoro at 24 kHz, and Kestrel hears 16 kHz. */
class ClipMicrophoneResampleTest {
    private fun tone(hertz: Double, rate: Int) = FloatArray(rate) { (0.5 * sin(2 * PI * hertz * it / rate)).toFloat() }

    // The root mean square of the middle half, away from the edges, where the filter sees silence beyond the samples.
    private fun level(samples: FloatArray): Double {
        val middle = samples.copyOfRange(samples.size / 4, samples.size * 3 / 4)
        return sqrt(middle.sumOf { it.toDouble() * it } / middle.size)
    }

    @Test
    fun aToneAbove8KilohertzGoesAndA1KilohertzToneKeepsItsLevel() {
        for (rate in listOf(22_050, 24_000)) {
            val original = level(tone(1_000.0, rate))
            val speech = ClipMicrophone.resample(tone(1_000.0, rate), rate)

            assertEquals(16_000, speech.size)
            assertEquals("1 kHz at $rate Hz", original, level(speech), original * 0.01)
            // Linear interpolation folded a 10 kHz tone back to 6 kHz at about half its level.
            val folded = level(ClipMicrophone.resample(tone(10_000.0, rate), rate))
            assertTrue("10 kHz at $rate Hz kept ${folded / original} of its level", folded < original * 0.01)
        }
    }
}
