package dev.kestrel.voice.sherpa

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import dev.kestrel.voice.SAMPLE_RATE
import dev.kestrel.voice.SpeechDetector
import dev.kestrel.voice.Utterance
import java.io.File

/**
 * Silero VAD through sherpa-onnx. The model takes the audio in windows of 512 samples, so this collects them.
 *
 * sherpa-onnx's own `maxSpeechDuration` does not cut an utterance; after 30 s it only lets a 0.1 s pause end it. So this
 * class cuts an utterance itself before it reaches 30 s. sherpa-onnx also counts samples in 32 bits, so during silence
 * this class restarts sherpa-onnx's count once an hour and keeps its own count in a `Long`.
 *
 * A call after [close] throws [IllegalStateException]; [close] waits for a call that is going on. Close the detector only
 * after the last call has returned.
 */
internal class SherpaSpeechDetector(model: File) : SpeechDetector {
    private val vad = Vad(
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = model.path,
                minSilenceDuration = END_SILENCE_SECONDS,
                minSpeechDuration = MIN_SPEECH_SECONDS,
                maxSpeechDuration = MAX_SPEECH_SECONDS,
                windowSize = WINDOW,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
        ),
    )
    private val window = FloatArray(WINDOW)
    private var filled = 0
    private var closed = false

    // The samples of the utterance that is going on, as far as sherpa-onnx has seen them.
    private var speechSamples = 0

    // The samples that sherpa-onnx took since its last reset, and the samples before that reset since the last finish().
    private var sinceReset = 0L
    private var beforeReset = 0L

    override val hearsSpeech: Boolean
        get() = synchronized(this) {
            check(!closed) { "The speech detector is closed" }
            vad.isSpeechDetected()
        }

    override fun accept(samples: FloatArray): List<Utterance> = synchronized(this) {
        check(!closed) { "The speech detector is closed" }
        val utterances = mutableListOf<Utterance>()
        var offset = 0
        while (offset < samples.size) {
            val count = minOf(WINDOW - filled, samples.size - offset)
            samples.copyInto(window, filled, offset, offset + count)
            filled += count
            offset += count
            if (filled == WINDOW) {
                acceptWindow(utterances)
                filled = 0
            }
        }
        utterances += utterances()
        utterances
    }

    private fun acceptWindow(utterances: MutableList<Utterance>) {
        vad.acceptWaveform(window)
        sinceReset += WINDOW
        if (!vad.isSpeechDetected()) {
            speechSamples = 0
            if (sinceReset >= RESET_SAMPLES) {
                // reset() drops the utterances that sherpa-onnx has not handed out yet.
                utterances += utterances()
                vad.reset()
                beforeReset += sinceReset
                sinceReset = 0
            }
            return
        }
        speechSamples += WINDOW
        // sherpa-onnx starts an utterance two windows and the minimum speech duration before the window in which it
        // noticed the speech. After flush(), the next window starts a new utterance exactly at the cut.
        if (speechSamples + 2 * WINDOW + MIN_SPEECH_SAMPLES >= MAX_SPEECH_SAMPLES) {
            vad.flush()
            speechSamples = 0
        }
    }

    override fun finish(): List<Utterance> = synchronized(this) {
        check(!closed) { "The speech detector is closed" }
        vad.flush()
        val utterances = utterances()
        vad.reset()
        filled = 0
        speechSamples = 0
        sinceReset = 0
        beforeReset = 0
        utterances
    }

    private fun utterances(): List<Utterance> {
        val utterances = mutableListOf<Utterance>()
        while (!vad.empty()) {
            val segment = vad.front()
            // A cut that falls into the silence that ends an utterance makes sherpa-onnx queue a segment without samples.
            if (segment.samples.isNotEmpty()) utterances += Utterance(beforeReset + segment.start, segment.samples)
            vad.pop()
        }
        return utterances
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            vad.release()
        }
    }

    private companion object {
        const val WINDOW = 512
        // Design spec, section 8.3.
        const val END_SILENCE_SECONDS = 0.7f
        const val MAX_SPEECH_SECONDS = 30f
        const val MAX_SPEECH_SAMPLES = 30 * SAMPLE_RATE
        // sherpa-onnx's default, passed explicitly because the cut at 30 s counts on it.
        const val MIN_SPEECH_SECONDS = 0.25f
        const val MIN_SPEECH_SAMPLES = SAMPLE_RATE / 4
        // An hour of audio, far below the 2^31 samples (about 37 hours) at which sherpa-onnx's count overflows.
        const val RESET_SAMPLES = 3_600L * SAMPLE_RATE
    }
}
