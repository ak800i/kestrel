package dev.kestrel.voice

/** The sample rate of the audio that the engines take: 16 kHz, as Kestrel records (design spec, section 8.1). */
const val SAMPLE_RATE = 16_000

/** An utterance that the speech detector found: its [samples] at 16 kHz, which start [startSample] samples into the audio. */
class Utterance(val startSample: Long, val samples: FloatArray) {
    val seconds: Float get() = samples.size / SAMPLE_RATE.toFloat()
}

/**
 * Splits audio into utterances (design spec, sections 8.2 and 8.3). An utterance ends after 0.7 s of silence and lasts at
 * most 30 s. Not thread-safe: feed it from one thread, and close it when it is no longer needed.
 */
interface SpeechDetector : AutoCloseable {
    /** Feeds 16 kHz samples, any number at a time, and returns the utterances that ended in them. */
    fun accept(samples: FloatArray): List<Utterance>

    /** Ends the audio: returns the utterance that is still going on, if any, and counts samples from 0 again. */
    fun finish(): List<Utterance>

    /** Whether the audio that came last sounds like speech. */
    val hearsSpeech: Boolean
}
