package dev.kestrel.voice

import dev.kestrel.voice.models.Engine
import dev.kestrel.voice.models.ModelFiles
import dev.kestrel.voice.models.ModelStatus
import dev.kestrel.voice.sherpa.BpeVocab
import dev.kestrel.voice.sherpa.KeywordLines
import dev.kestrel.voice.sherpa.SherpaKeywordSpotter
import dev.kestrel.voice.sherpa.SherpaKokoroSynthesizer
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
    /** The voice of [options]: each voice needs only its own models and espeak-ng's data (section 8.2). */
    suspend fun synthesizer(options: VoiceOptions = VoiceOptions()): Synthesizer = when (options.voice) {
        VoiceChoice.PIPER -> load(Engine.TTS, PIPER_MODELS) {
            SherpaSynthesizer(models.file(TTS_MODEL), models.file(TTS_TOKENS), models.file(ESPEAK_DATA), options)
        }
        VoiceChoice.KOKORO -> load(Engine.TTS, KOKORO_MODELS) {
            SherpaKokoroSynthesizer(
                models.file(KOKORO_MODEL),
                models.file(KOKORO_VOICES),
                models.file(KOKORO_TOKENS),
                models.file(ESPEAK_DATA),
                options,
            )
        }
    }

    /**
     * The keyword spotter's boost and threshold for the spotters that load from now on (design spec, section 8.2). Only
     * the debug hook voice-spotter changes it, so that the A71 session can try other values without a new build (plan 2E);
     * a change lasts until the end of the process. Any thread may set it.
     */
    @Volatile
    var keywordTuning = KeywordTuning()

    /** The file of the Kokoro model that [synthesizer] loads, which names a measure of Kokoro's speed (plan 2E). */
    val kokoroModel: String get() = KOKORO_MODEL

    /**
     * Kokoro with the model file [model], a path in the models folder such as "tts-kokoro-int8/model.int8.onnx", and
     * [threads] threads, for the voice device test that compares Kokoro's models on a phone (plan 2E); Kestrel's voice
     * session uses [synthesizer]. Throws [ModelsNotReadyException] when the model's files are missing or damaged.
     */
    suspend fun kokoro(model: String, threads: Int): Synthesizer {
        val ids = if (model == KOKORO_INT8_MODEL) KOKORO_MODELS + KOKORO_INT8_ID else KOKORO_MODELS
        return load(Engine.TTS, ids) {
            SherpaKokoroSynthesizer(
                models.file(model),
                models.file(KOKORO_VOICES),
                models.file(KOKORO_TOKENS),
                models.file(ESPEAK_DATA),
                VoiceOptions(voice = VoiceChoice.KOKORO),
                threads,
            )
        }
    }

    suspend fun speechDetector(): SpeechDetector = load(Engine.VAD) {
        SherpaSpeechDetector(models.file(VAD_MODEL))
    }

    suspend fun keywordSpotter(): KeywordSpotter = load(Engine.KWS) {
        // sherpa-onnx reads the keywords from a file.
        val keywords = writeWorkFile("keywords.txt", KeywordLines.text(keywordTuning))
        SherpaKeywordSpotter(models.file(KWS_ENCODER), models.file(KWS_DECODER), models.file(KWS_JOINER), models.file(KWS_TOKENS), keywords)
    }

    suspend fun recognizer(): Recognizer = load(Engine.ASR) {
        val vocab = writeWorkFile("parakeet-bpe.vocab", BpeVocab.fromTokens(models.file(ASR_TOKENS).readText()))
        SherpaRecognizer(models.file(ASR_ENCODER), models.file(ASR_DECODER), models.file(ASR_JOINER), models.file(ASR_TOKENS), vocab)
    }

    /**
     * Checks [engine]'s models and creates the engine on [Dispatchers.IO]: the models whose ids are in [ids], or without
     * [ids] every model of the engine that is not optional. If the caller is cancelled while the engine loads,
     * `withContext` drops the loaded engine, so this closes it before the cancellation goes on.
     */
    internal suspend fun <T : AutoCloseable> load(engine: Engine, ids: Set<String>? = null, create: () -> T): T {
        var created: T? = null
        try {
            return withContext(Dispatchers.IO) {
                requireReady(engine, ids)
                create().also { created = it }
            }
        } catch (e: CancellationException) {
            // An exception from close() must not hide the cancellation.
            runCatching { created?.close() }.exceptionOrNull()?.let(e::addSuppressed)
            throw e
        }
    }

    private suspend fun requireReady(engine: Engine, ids: Set<String>?) {
        val notReady = models.check().filter { status ->
            status.model.engine == engine && !status.ready && (ids?.contains(status.model.id) ?: !status.model.optional)
        }
        if (notReady.isNotEmpty()) throw ModelsNotReadyException(engine, notReady)
    }

    /**
     * Writes [text] to [name] in [workFolder] through a temporary file of its own, so that two loads at once never read a
     * file that the other is writing: sherpa-onnx ends the process on a torn word piece. A process that ended during a
     * write left its temporary file behind, so this first deletes the temporary files of [name]. The writes take one lock,
     * so that this never deletes the temporary file of a write that is going on in this process.
     */
    internal fun writeWorkFile(name: String, text: String): File = synchronized(WORK_FILES) {
        workFolder.mkdirs()
        val leftover = Regex(Regex.escape(name) + "-?[0-9]+" + Regex.escape(TEMPORARY))
        workFolder.listFiles { file -> leftover.matches(file.name) }?.forEach { it.delete() }
        val file = File(workFolder, name)
        val temporary = File.createTempFile(name, TEMPORARY, workFolder)
        try {
            temporary.writeText(text)
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temporary.delete()
        }
        file
    }

    internal companion object {
        // File.createTempFile names a temporary file after its prefix, a random number and this suffix.
        private const val TEMPORARY = ".tmp"
        private val WORK_FILES = Any()
        const val VAD_MODEL = "vad-silero/silero_vad.onnx"
        const val TTS_MODEL = "tts-piper-ljspeech/en_US-ljspeech-medium.onnx"
        const val TTS_TOKENS = "tts-piper-ljspeech/tokens.txt"
        const val ESPEAK_DATA = "espeak-ng-data"

        // The ids in models/manifest.json of the models that each voice needs.
        val PIPER_MODELS = setOf("tts-piper-ljspeech", "tts-espeak-ng-data")
        val KOKORO_MODELS = setOf("tts-kokoro", "tts-espeak-ng-data")
        const val KOKORO_MODEL = "tts-kokoro/model.onnx"
        const val KOKORO_VOICES = "tts-kokoro/voices.bin"
        const val KOKORO_TOKENS = "tts-kokoro/tokens.txt"

        // Kokoro's int8 model, which only a voice device test loads until the A71 session has compared it (plan 2E).
        const val KOKORO_INT8_ID = "tts-kokoro-int8"
        const val KOKORO_INT8_MODEL = "tts-kokoro-int8/model.int8.onnx"
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
