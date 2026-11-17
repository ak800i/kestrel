package dev.kestrel.voice.sherpa

import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import dev.kestrel.voice.Synthesizer
import dev.kestrel.voice.VoiceOptions
import java.io.File

/**
 * Kokoro v1.0's voice `af_heart` through sherpa-onnx (design spec, section 8.2; research r11), which gets the phonemes of
 * English text from espeak-ng's data in [espeakData], as Piper does. A call to [synthesize] after [close] throws
 * [IllegalStateException]; [close] waits for a call that is going on. Close the voice only after the last call has
 * returned. [sampleRate] is stored when the voice loads, so it can be read at any time.
 */
internal class SherpaKokoroSynthesizer(
    model: File,
    voices: File,
    tokens: File,
    espeakData: File,
    private val options: VoiceOptions,
) : Synthesizer {
    private val tts = OfflineTts(
        config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = model.path,
                    voices = voices.path,
                    tokens = tokens.path,
                    dataDir = espeakData.path,
                    // With a language, sherpa-onnx 1.13.8 reads all text but Chinese with espeak-ng, so the voice needs no
                    // lexicon file.
                    lang = LANGUAGE,
                ),
                numThreads = THREADS,
            ),
        ),
    )

    override val sampleRate: Int = tts.sampleRate()

    private var closed = false

    override fun synthesize(text: String): FloatArray = synchronized(this) {
        check(!closed) { "The voice is closed" }
        if (!kokoroCanSay(text)) return FloatArray(0)
        tts.generate(text, sid = SPEAKER, speed = options.speed).samples
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            tts.release()
        }
    }

    private companion object {
        const val LANGUAGE = "en-us"

        // af_heart in kokoro-multi-lang-v1_0's voices.bin.
        const val SPEAKER = 3

        // As for Piper; the A71 session measures the voice's speed.
        const val THREADS = 2
    }
}

/**
 * Whether Kokoro may get [text]: not when it has a Chinese character, because sherpa-onnx 1.13.8 logs every word of a
 * Chinese text that Kokoro's lexicon lacks, also without debug output (design spec, section 13.5). Kestrel has no
 * lexicon for Chinese, so the voice could not say such a text anyway.
 */
internal fun kokoroCanSay(text: String): Boolean = text.none { it in '\u4e00'..'\u9fff' }
