package dev.kestrel.voice.listen

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordFramesTest {
    @Test
    fun samplesThatArriveInPartsMakeOneWholeFrame() {
        var next: Short = 0
        val arrivals = ArrayDeque(listOf(100, 0, 150, 200))
        val frames = RecordFrames { samples, offset, count ->
            val arrived = minOf(arrivals.removeFirst(), count)
            for (index in offset until offset + arrived) samples[index] = next++
            arrived
        }
        assertNull(frames.next())
        // 220 samples are missing: 13.75 ms at 16 kHz.
        assertEquals(13_750_000L, frames.nanosToNextFrame())
        val frame = frames.next()!!
        assertArrayEquals(FloatArray(RecordFrames.FRAME) { it / 32_768f }, frame, 0f)
    }

    @Test(expected = IllegalStateException::class)
    fun anErrorOfTheRecordingIsAFailure() {
        RecordFrames { _, _, _ -> -6 }.next()
    }
}
