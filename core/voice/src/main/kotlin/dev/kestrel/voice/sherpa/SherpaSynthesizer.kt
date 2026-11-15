package dev.kestrel.voice.sherpa

import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import dev.kestrel.voice.Synthesizer
import dev.kestrel.voice.VoiceOptions
import java.io.File

/**
 * A Piper voice through sherpa-onnx, which gets its phonemes from espeak-ng's data in [espeakData] (B38). A call to
 * [synthesize] after [close] throws [IllegalStateException]; [close] waits for a call that is going on. Close the voice
 * only after the last call has returned. [sampleRate] is stored when the voice loads, so it can be read at any time.
 */
internal class SherpaSynthesizer(model: File, tokens: File, espeakData: File, private val options: VoiceOptions) : Synthesizer {
    private val tts = OfflineTts(
        config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = model.path,
                    tokens = tokens.path,
                    dataDir = espeakData.path,
                    noiseScale = NOISE_SCALE * options.variation,
                    noiseScaleW = NOISE_SCALE_W * options.variation,
                ),
                numThreads = THREADS,
            ),
        ),
    )

    override val sampleRate: Int = tts.sampleRate()

    private var closed = false

    override fun synthesize(text: String): FloatArray = synchronized(this) {
        check(!closed) { "The voice is closed" }
        tts.generate(text, sid = 0, speed = options.speed).samples
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            tts.release()
        }
    }

    private companion object {
        // sherpa-onnx's defaults for VITS voices, such as Piper's.
        const val NOISE_SCALE = 0.667f
        const val NOISE_SCALE_W = 0.8f
        const val THREADS = 2
    }
}
