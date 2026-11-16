package dev.kestrel.app.voice

import dev.kestrel.app.debug.ClipMicrophone
import dev.kestrel.voice.SAMPLE_RATE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** The stand-in microphone of the device tests and the debug hooks. */
class ClipMicrophoneTest {
    @Test
    fun aSilencedRecordingDeliversZerosAndOnlyARecordingCanBeSilenced() {
        val clip = ClipMicrophone()
        assertThrows(IllegalStateException::class.java) { clip.silence(true) }
        assertFalse(clip.silenced.value)

        val loudest = LinkedBlockingQueue<Float>()
        clip.start(onFrame = { frame -> loudest.add(frame.maxOf { abs(it) }) }, onError = {})
        try {
            clip.silence(true)
            clip.play(FloatArray(SAMPLE_RATE / 5) { 0.5f })
            // The clip takes 0.2 s; the microphone delivers zeros all along.
            Thread.sleep(1_000)
            assertEquals(0f, loudest.max(), 0f)

            clip.silence(false)
            clip.play(FloatArray(SAMPLE_RATE / 5) { 0.5f })
            val played = generateSequence { loudest.poll(2, TimeUnit.SECONDS) }.firstOrNull { it > 0f }
            assertNotNull("The clip did not play", played)
        } finally {
            clip.stop()
        }
        assertFalse(clip.silenced.value)
    }
}
