package dev.kestrel.voice.listen

import java.util.Locale
import kotlin.math.log10

/**
 * The loudness of a recording's samples since the last [report], for the debug hooks voice-route and voice-record (plan
 * 4D, H7), so that the A71 checks set the PC's volume from what the phone hears: the RMS level of all samples and of the
 * loudest frame, in dBFS. Levels only, never audio. Any thread may call it.
 */
class LevelMeter {
    private var sumSquares = 0.0
    private var samples = 0L
    private var loudestFrame = 0.0

    /** Adds one frame of samples between -1 and 1. */
    @Synchronized
    fun add(frame: FloatArray) {
        if (frame.isEmpty()) return
        var sum = 0.0
        for (sample in frame) sum += sample.toDouble() * sample
        sumSquares += sum
        samples += frame.size
        loudestFrame = maxOf(loudestFrame, sum / frame.size)
    }

    /** "rms=<dBFS> frameRms=<dBFS>" since the last report, or none for both without a frame; then it starts again. */
    @Synchronized
    fun report(): String {
        val text = if (samples == 0L) "rms=none frameRms=none" else "rms=${dbfs(sumSquares / samples)} frameRms=${dbfs(loudestFrame)}"
        sumSquares = 0.0
        samples = 0L
        loudestFrame = 0.0
        return text
    }

    companion object {
        private const val FLOOR = -120.0

        /** The level of a mean square in dBFS, with one decimal, and -120.0 at the lowest, for silence. */
        fun dbfs(meanSquare: Double): String =
            String.format(Locale.ROOT, "%.1f", if (meanSquare <= 0.0) FLOOR else maxOf(FLOOR, 10 * log10(meanSquare)))
    }
}
