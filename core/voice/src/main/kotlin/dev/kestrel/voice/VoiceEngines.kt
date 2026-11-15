package dev.kestrel.voice

import dev.kestrel.voice.models.Engine
import dev.kestrel.voice.models.ModelFiles
import dev.kestrel.voice.models.ModelStatus
import dev.kestrel.voice.sherpa.KeywordLines
import dev.kestrel.voice.sherpa.SherpaKeywordSpotter
import dev.kestrel.voice.sherpa.SherpaSpeechDetector
import dev.kestrel.voice.sherpa.SherpaSynthesizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Creates Kestrel's speech engines from the model files that [models] checks (design spec, section 8.2). Loading a model
 * takes up to a few seconds, so the engines are created on [Dispatchers.IO], and the caller closes each engine. A damaged
 * model file could end Kestrel's process inside sherpa-onnx, so each call first checks its engine's models and throws
 * [ModelsNotReadyException] when they are not ready. [workFolder] is a private folder for files that the engines derive
 * from the models.
 */
class VoiceEngines(private val models: ModelFiles, private val workFolder: File) {
    suspend fun synthesizer(options: VoiceOptions = VoiceOptions()): Synthesizer = withContext(Dispatchers.IO) {
        requireReady(Engine.TTS)
        SherpaSynthesizer(models.file(TTS_MODEL), models.file(TTS_TOKENS), models.file(ESPEAK_DATA), options)
    }

    suspend fun speechDetector(): SpeechDetector = withContext(Dispatchers.IO) {
        requireReady(Engine.VAD)
        SherpaSpeechDetector(models.file(VAD_MODEL))
    }

    suspend fun keywordSpotter(): KeywordSpotter = withContext(Dispatchers.IO) {
        requireReady(Engine.KWS)
        // sherpa-onnx reads the keywords from a file.
        val keywords = File(workFolder, "keywords.txt")
        keywords.parentFile?.mkdirs()
        keywords.writeText(KeywordLines.text())
        SherpaKeywordSpotter(models.file(KWS_ENCODER), models.file(KWS_DECODER), models.file(KWS_JOINER), models.file(KWS_TOKENS), keywords)
    }

    private suspend fun requireReady(engine: Engine) {
        val notReady = models.check().filter { it.model.engine == engine && !it.ready }
        if (notReady.isNotEmpty()) throw ModelsNotReadyException(engine, notReady)
    }

    internal companion object {
        const val VAD_MODEL = "vad-silero/silero_vad.onnx"
        const val TTS_MODEL = "tts-piper-ljspeech/en_US-ljspeech-medium.onnx"
        const val TTS_TOKENS = "tts-piper-ljspeech/tokens.txt"
        const val ESPEAK_DATA = "espeak-ng-data"
        const val KWS_ENCODER = "kws-gigaspeech/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        const val KWS_DECODER = "kws-gigaspeech/decoder-epoch-12-avg-2-chunk-16-left-64.onnx"
        const val KWS_JOINER = "kws-gigaspeech/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        const val KWS_TOKENS = "kws-gigaspeech/tokens.txt"
    }
}

/** The models of [engine] are missing or damaged; [statuses] say which files. */
class ModelsNotReadyException(val engine: Engine, val statuses: List<ModelStatus>) :
    IllegalStateException("The models of $engine are missing or damaged")
