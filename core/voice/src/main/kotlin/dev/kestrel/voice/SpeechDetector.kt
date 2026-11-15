package dev.kestrel.voice

/** The sample rate of the audio that the engines take: 16 kHz, as Kestrel records (design spec, section 8.1). */
const val SAMPLE_RATE = 16_000

/** An utterance that the speech detector found: its [samples] at 16 kHz, which start [startSample] samples into the audio. */
class Utterance(val startSample: Long, val samples: FloatArray) {
    val seconds: Float get() = samples.size / SAMPLE_RATE.toFloat()
}

/**
 * Splits audio into utterances (design spec, sections 8.2 and 8.3). An utterance ends after 0.7 s of silence, lasts at
 * least 0.25 s and at most 30 s: longer speech is cut into utterances of just under 30 s, and each one starts where the
 * one before it ended. When the speech ends less than 0.25 s after such a cut, that rest is dropped.
 * Not thread-safe: feed it from one thread. Close it when it is no longer needed, and only after the last call has
 * returned; Kestrel's detector throws [IllegalStateException] when it is called after [close].
 */
interface SpeechDetector : AutoCloseable {
    /** Feeds 16 kHz samples, any number at a time, and returns the utterances that ended in them. */
    fun accept(samples: FloatArray): List<Utterance>

    /** Ends the audio: returns the utterance that is still going on, if any, and counts samples from 0 again. */
    fun finish(): List<Utterance>

    /**
     * Whether an utterance is going on: from about 0.3 s after speech starts until the utterance ends. Right after a cut
     * at 30 s, it reads false for a 20 ms frame or two although the speech goes on.
     */
    val hearsSpeech: Boolean
}
