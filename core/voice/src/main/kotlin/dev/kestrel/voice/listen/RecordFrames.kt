package dev.kestrel.voice.listen

import dev.kestrel.voice.SAMPLE_RATE

/**
 * The 20 ms frames of a recording that is read without blocking, such as an `AudioRecord` with `READ_NON_BLOCKING`.
 * [read] copies at most the asked number of 16-bit samples that have arrived into the array at the offset, and returns
 * how many it copied, or a negative error code. The samples of an incomplete frame wait for the rest, so none is lost.
 */
internal class RecordFrames(private val read: (samples: ShortArray, offset: Int, count: Int) -> Int) : FrameSource {
    private val samples = ShortArray(FRAME)
    private var filled = 0

    override fun next(): FloatArray? {
        while (filled < FRAME) {
            val count = read(samples, filled, FRAME - filled)
            check(count >= 0) { "The microphone failed with error $count" }
            if (count == 0) return null
            filled += count
        }
        filled = 0
        return FloatArray(FRAME) { samples[it] / 32_768f }
    }

    override fun nanosToNextFrame(): Long = (FRAME - filled) * 1_000_000_000L / SAMPLE_RATE

    companion object {
        const val FRAME = SAMPLE_RATE / 50
    }
}
