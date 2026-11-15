package dev.kestrel.voice

/**
 * Kestrel's voice (design spec, section 8.4). Not thread-safe: use one instance from one thread at a time. Close it when
 * it is no longer needed, because it holds the voice model in native memory, and only after the last call has returned;
 * Kestrel's voice throws [IllegalStateException] when [synthesize] is called after [close].
 */
interface Synthesizer : AutoCloseable {
    /** The sample rate of [synthesize]'s output, in Hz. Piper's voices use 22,050 Hz. */
    val sampleRate: Int

    /** Speaks [text] into samples in [-1, 1] at [sampleRate]. Returns no samples when the voice cannot say the text. */
    fun synthesize(text: String): FloatArray
}

/**
 * How Kestrel's voice sounds. A [speed] of 1 is the voice's normal pace, and it must be above 0. [variation] scales the
 * small random variation in the voice's sound: 1 is the voice's normal amount, and 0 makes the voice say a text the same
 * way every time, which the device tests need.
 */
data class VoiceOptions(val speed: Float = 1f, val variation: Float = 1f) {
    init {
        // With a speed of 0 or less, sherpa-onnx returns no samples and says why only in its log.
        require(speed > 0f) { "The speed must be above 0" }
    }
}
