package dev.kestrel.voice.sherpa

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import dev.kestrel.voice.SAMPLE_RATE
import dev.kestrel.voice.SpeechDetector
import dev.kestrel.voice.Utterance
import java.io.File

/** Silero VAD through sherpa-onnx. The model takes the audio in windows of 512 samples, so this collects them. */
internal class SherpaSpeechDetector(model: File) : SpeechDetector {
    private val vad = Vad(
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = model.path,
                minSilenceDuration = END_SILENCE_SECONDS,
                maxSpeechDuration = MAX_SPEECH_SECONDS,
                windowSize = WINDOW,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
        ),
    )
    private val window = FloatArray(WINDOW)
    private var filled = 0

    override val hearsSpeech: Boolean get() = vad.isSpeechDetected()

    override fun accept(samples: FloatArray): List<Utterance> {
        var offset = 0
        while (offset < samples.size) {
            val count = minOf(WINDOW - filled, samples.size - offset)
            samples.copyInto(window, filled, offset, offset + count)
            filled += count
            offset += count
            if (filled == WINDOW) {
                vad.acceptWaveform(window)
                filled = 0
            }
        }
        return utterances()
    }

    override fun finish(): List<Utterance> {
        vad.flush()
        val utterances = utterances()
        vad.reset()
        filled = 0
        return utterances
    }

    private fun utterances(): List<Utterance> {
        val utterances = mutableListOf<Utterance>()
        while (!vad.empty()) {
            val segment = vad.front()
            utterances += Utterance(segment.start.toLong(), segment.samples)
            vad.pop()
        }
        return utterances
    }

    override fun close() = vad.release()

    private companion object {
        const val WINDOW = 512
        // Design spec, section 8.3.
        const val END_SILENCE_SECONDS = 0.7f
        const val MAX_SPEECH_SECONDS = 30f
    }
}
