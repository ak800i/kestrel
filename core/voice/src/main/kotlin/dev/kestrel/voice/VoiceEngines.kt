package dev.kestrel.voice

import dev.kestrel.voice.models.Engine
import dev.kestrel.voice.models.ModelFiles
import dev.kestrel.voice.models.ModelStatus
import dev.kestrel.voice.sherpa.BpeVocab
import dev.kestrel.voice.sherpa.KeywordLines
import dev.kestrel.voice.sherpa.SherpaKeywordSpotter
import dev.kestrel.voice.sherpa.SherpaRecognizer
import dev.kestrel.voice.sherpa.SherpaSpeechDetector
import dev.kestrel.voice.sherpa.SherpaSynthesizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Creates Kestrel's speech engines from the model files that [models] checks (design spec, section 8.2). Loading a model
 * takes up to a few seconds, so the engines are created on [Dispatchers.IO], and the caller closes each engine. A damaged
 * model file could end Kestrel's process inside sherpa-onnx, so each call first checks its engine's models and throws
 * [ModelsNotReadyException] when they are not ready. [workFolder] is a private folder for files that the engines derive
 * from the models.
 */
class VoiceEngines(private val models: ModelFiles, private val workFolder: File) {
    suspend fun synthesizer(options: VoiceOptions = VoiceOptions()): Synthesizer = load(Engine.TTS) {
        SherpaSynthesizer(models.file(TTS_MODEL), models.file(TTS_TOKENS), models.file(ESPEAK_DATA), options)
    }

    suspend fun speechDetector(): SpeechDetector = load(Engine.VAD) {
        SherpaSpeechDetector(models.file(VAD_MODEL))
    }

    suspend fun keywordSpotter(): KeywordSpotter = load(Engine.KWS) {
        // sherpa-onnx reads the keywords from a file.
        val keywords = writeWorkFile("keywords.txt", KeywordLines.text())
        SherpaKeywordSpotter(models.file(KWS_ENCODER), models.file(KWS_DECODER), models.file(KWS_JOINER), models.file(KWS_TOKENS), keywords)
    }

    suspend fun recognizer(): Recognizer = load(Engine.ASR) {
        val vocab = writeWorkFile("parakeet-bpe.vocab", BpeVocab.fromTokens(models.file(ASR_TOKENS).readText()))
        SherpaRecognizer(models.file(ASR_ENCODER), models.file(ASR_DECODER), models.file(ASR_JOINER), models.file(ASR_TOKENS), vocab)
    }

    /**
     * Checks [engine]'s models and creates the engine on [Dispatchers.IO]. If the caller is cancelled while the engine
     * loads, `withContext` drops the loaded engine, so this closes it before the cancellation goes on.
     */
    internal suspend fun <T : AutoCloseable> load(engine: Engine, create: () -> T): T {
        var created: T? = null
        try {
            return withContext(Dispatchers.IO) {
                requireReady(engine)
                create().also { created = it }
            }
        } catch (e: CancellationException) {
            created?.close()
            throw e
        }
    }

    private suspend fun requireReady(engine: Engine) {
        val notReady = models.check().filter { it.model.engine == engine && !it.ready }
        if (notReady.isNotEmpty()) throw ModelsNotReadyException(engine, notReady)
    }

    /**
     * Writes [text] to [name] in [workFolder] through a temporary file of its own, so that two loads at once never read a
     * file that the other is writing: sherpa-onnx ends the process on a torn word piece.
     */
    private fun writeWorkFile(name: String, text: String): File {
        workFolder.mkdirs()
        val file = File(workFolder, name)
        val temporary = File.createTempFile(name, ".tmp", workFolder)
        try {
            temporary.writeText(text)
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temporary.delete()
        }
        return file
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
        const val ASR_ENCODER = "asr-parakeet-v3/encoder.int8.onnx"
        const val ASR_DECODER = "asr-parakeet-v3/decoder.int8.onnx"
        const val ASR_JOINER = "asr-parakeet-v3/joiner.int8.onnx"
        const val ASR_TOKENS = "asr-parakeet-v3/tokens.txt"
    }
}

/** The models of [engine] are missing or damaged; [statuses] say which files. */
class ModelsNotReadyException(val engine: Engine, val statuses: List<ModelStatus>) :
    IllegalStateException("The models of $engine are missing or damaged")
