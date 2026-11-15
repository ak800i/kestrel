package dev.kestrel.voice.sherpa

import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import dev.kestrel.voice.Synthesizer
import dev.kestrel.voice.VoiceOptions
import java.io.File

/** A Piper voice through sherpa-onnx, which gets its phonemes from espeak-ng's data in [espeakData] (B38). */
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

    override fun synthesize(text: String): FloatArray = tts.generate(text, sid = 0, speed = options.speed).samples

    override fun close() = tts.release()

    private companion object {
        // sherpa-onnx's defaults for VITS voices, such as Piper's.
        const val NOISE_SCALE = 0.667f
        const val NOISE_SCALE_W = 0.8f
        const val THREADS = 2
    }
}
