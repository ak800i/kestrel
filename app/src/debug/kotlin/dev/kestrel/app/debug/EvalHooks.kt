package dev.kestrel.app.debug

import android.content.Context
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import dev.kestrel.app.AppGraph
import dev.kestrel.app.debug.TestHooks.HookError
import dev.kestrel.app.listen.BoostWordBudget
import dev.kestrel.app.listen.BoostWordSource
import dev.kestrel.app.listen.LauncherAppLabels
import dev.kestrel.app.orchestrator.Request
import dev.kestrel.app.orchestrator.Requests
import dev.kestrel.commands.CommandVocabulary
import dev.kestrel.commands.text.WordErrors
import dev.kestrel.voice.ModelsNotReadyException
import dev.kestrel.voice.Recognizer
import dev.kestrel.voice.SAMPLE_RATE
import dev.kestrel.voice.SpeechDetector
import dev.kestrel.voice.Synthesizer
import dev.kestrel.voice.VoiceChoice
import dev.kestrel.voice.VoiceOptions
import dev.kestrel.voice.listen.LevelMeter
import dev.kestrel.voice.listen.MicCapture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The hooks of the voice corpus and its measurements (design spec, sections 5.3 and 16.4), which scripts\make-corpus.ps1,
 * scripts\voice-eval.ps1 and scripts\a71-session.ps1 use. They read and write clips of 16 kHz in the folder `eval` of
 * Kestrel's external files folder, which adb can reach. VOICE_SYNTH says a text in one of Kestrel's voices into a clip.
 * VOICE_RECORD starts recording the microphone into a clip and answers once the microphone records; `result` then asks
 * for the recording's answer. VOICE_EVAL cuts a clip's speech out with Kestrel's speech detector, as Kestrel's pipeline
 * does, transcribes it with the boost words of Kestrel's voice session, and compares the transcript with the text that
 * was to be said; for a line of the corpus with its group (`group`), it also uses the command vocabulary and the labels
 * of the line's scene as the screen's boost words, as each utterance of Kestrel's voice session does, and for a line of
 * the instant commands it answers whether the transcript leads to the same action (sub-project 3). Their answers hold only counts and times, except that
 * VOICE_EVAL with `reveal` adds the transcript, which it does only for a generated clip, whose name starts with
 * [GENERATED]. They log nothing.
 */
internal class EvalHooks(private val graph: AppGraph, private val context: Context) {
    suspend fun run(command: String, extras: Bundle): String = when (command) {
        "VOICE_SYNTH" -> if (extras.getBoolean(EXTRA_CLOSE, false)) closeVoice() else synthesize(extras)
        "VOICE_RECORD" -> if (extras.getBoolean(EXTRA_RESULT, false)) recordingResult() else record(extras)
        "VOICE_EVAL" -> when {
            extras.getBoolean(EXTRA_LOAD, false) -> load()
            extras.getBoolean(EXTRA_CLOSE, false) -> closeRecognizer()
            else -> evaluate(extras)
        }
        else -> throw HookError("unknown command '$command'")
    }

    private fun folder(): File =
        File(context.getExternalFilesDir(null) ?: throw HookError("Kestrel's external files folder is not available"), FOLDER)
            .apply { mkdirs() }

    private fun clip(extras: Bundle): File {
        val name = extras.getString(EXTRA_FILE)
        if (name == null || !CLIP_NAME.matches(name)) throw HookError("add --es file with a name of letters, digits, '-' and '_'")
        return File(folder(), "$name.wav")
    }

    // Kestrel's voice session would hold a second recogniser and use the microphone.
    private fun requireOff() {
        if (graph.listening.sessionOpen.value) throw HookError("turn Kestrel off first, with voice -Send off")
    }

    private suspend fun synthesize(extras: Bundle): String {
        val text = extras.getString(EXTRA_TEXT)?.takeIf { it.isNotBlank() } ?: throw HookError("add --es text <text>")
        val file = clip(extras)
        val choice = when (extras.getString(EXTRA_VOICE) ?: "piper") {
            "piper" -> VoiceChoice.PIPER
            "kokoro" -> VoiceChoice.KOKORO
            else -> throw HookError("add --es voice with piper or kokoro")
        }
        val samples = voiceLock.withLock {
            val current = voice?.takeIf { voiceChoice == choice } ?: run {
                voice?.close()
                voice = null
                val loaded = try {
                    // Without variation, the voice says a text the same way every time.
                    graph.voice.synthesizer(VoiceOptions(variation = 0f, voice = choice))
                } catch (e: ModelsNotReadyException) {
                    throw HookError("the voice's model is missing; run scripts\\push-models.ps1" + if (choice == VoiceChoice.KOKORO) " -Kokoro" else "")
                }
                voice = loaded
                voiceChoice = choice
                loaded
            }
            withContext(Dispatchers.Default) { ClipMicrophone.speech(current, text) }
        }
        withContext(Dispatchers.IO) { Wav.write(file, samples) }
        return "wrote ${seconds(samples.size)} s"
    }

    private suspend fun closeVoice(): String {
        voiceLock.withLock {
            voice?.close()
            voice = null
        }
        return "closed the voice"
    }

    // Answers once the microphone records, so that the A71 session asks the owner to speak only then; the recording goes
    // on in the background, longer than a hook may run.
    private fun record(extras: Bundle): String {
        val file = clip(extras)
        val seconds = extras.getInt(EXTRA_SECONDS, 0)
        if (seconds !in 1..MAX_RECORDING_SECONDS) throw HookError("add --ei seconds with 1 to $MAX_RECORDING_SECONDS")
        requireOff()
        synchronized(lock) {
            if (recording === Recording.Running) throw HookError("a recording runs; ask for its answer with --ez result true")
            recording = null
        }
        val frames = CopyOnWriteArrayList<FloatArray>()
        val errors = CopyOnWriteArrayList<Exception>()
        val microphone = MicCapture(context)
        try {
            microphone.start(onFrame = { frames += it }, onError = { errors += it })
        } catch (e: SecurityException) {
            microphone.stop()
            throw HookError("Kestrel may not record; allow the microphone")
        } catch (e: IllegalStateException) {
            // A start that failed after the microphone opened leaves it open until a stop.
            microphone.stop()
            throw HookError("the microphone did not start")
        }
        synchronized(lock) {
            recording = Recording.Running
            graph.scope.launch {
                val ended = try {
                    delay(seconds * 1_000L)
                    val silenced = microphone.silenced.value
                    microphone.stop()
                    if (errors.isEmpty()) {
                        Recording.Ended(save(file, frames, silenced), null)
                    } else {
                        Recording.Ended(null, "the microphone failed")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Only the class, never a message (section 13.5).
                    Recording.Ended(null, "the clip could not be written (${e.javaClass.simpleName})")
                } finally {
                    microphone.stop()
                }
                synchronized(lock) { recording = ended }
            }
        }
        return "recording"
    }

    private suspend fun save(file: File, frames: List<FloatArray>, silenced: Boolean): String {
        val samples = FloatArray(frames.sumOf { it.size })
        var offset = 0
        for (frame in frames) {
            frame.copyInto(samples, offset)
            offset += frame.size
        }
        withContext(Dispatchers.IO) { Wav.write(file, samples) }
        val loudest = ((samples.maxOfOrNull { abs(it) } ?: 0f) * 100).roundToInt()
        // The levels of plan 4D's hook H7, frame by frame as the microphone delivered them.
        val level = LevelMeter()
        frames.forEach(level::add)
        return "recorded ${seconds(samples.size)} s, loudest $loudest %, silenced=$silenced, ${level.report()}"
    }

    private fun recordingResult(): String {
        val current = synchronized(lock) { recording.also { if (it is Recording.Ended) recording = null } }
        return when (current) {
            null -> throw HookError("no recording; start one with --es file and --ei seconds")
            Recording.Running -> "recording"
            is Recording.Ended -> current.answer ?: throw HookError(current.failure ?: "the recording failed")
        }
    }

    private fun load(): String {
        synchronized(lock) {
            when (val current = evaluation) {
                is Evaluation.Ready -> return "ready words=${current.words.size} kept=${current.kept}"
                is Evaluation.Loading -> return "loading"
                is Evaluation.Failed -> {
                    // The next load tries again.
                    evaluation = null
                    return "failed: ${current.what}"
                }
                null -> Unit
            }
            requireOff()
            folder()
            evaluation = Evaluation.Loading(
                graph.scope.launch {
                    val self = coroutineContext.job
                    val loaded = try {
                        loadEngines()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Only the class, never a message (section 13.5).
                        Evaluation.Failed(e.javaClass.simpleName)
                    }
                    synchronized(lock) {
                        // A close, or a newer load after it, came first.
                        if ((evaluation as? Evaluation.Loading)?.job === self) {
                            evaluation = loaded
                        } else {
                            (loaded as? Evaluation.Ready)?.close()
                        }
                    }
                },
            )
        }
        return "loading"
    }

    // The words come first, so that a cancellation while they are read leaves no engine open; VoiceEngines closes an
    // engine whose load a cancellation drops. The words are those of Kestrel's voice session: "Kestrel" and the names of
    // the apps. A failed read of the names leaves "Kestrel" alone, and nothing is logged.
    private suspend fun loadEngines(): Evaluation.Ready {
        val words = BoostWordSource(LauncherAppLabels(context)) { _, _ -> }.words()
        val detector = graph.voice.speechDetector()
        var recognizer: Recognizer? = null
        try {
            recognizer = graph.voice.recognizer()
            return Evaluation.Ready(recognizer, detector, words, recognizer.keptBoostWords(words))
        } catch (e: Throwable) {
            recognizer?.close()
            detector.close()
            throw e
        }
    }

    private suspend fun closeRecognizer(): String {
        // After an evaluation that runs, which uses the engines.
        val closing = evaluationLock.withLock { synchronized(lock) { evaluation.also { evaluation = null } } }
        when (closing) {
            is Evaluation.Ready -> closing.close()
            is Evaluation.Loading -> closing.job.cancel()
            else -> Unit
        }
        return "closed the recogniser"
    }

    private suspend fun evaluate(extras: Bundle): String {
        val file = clip(extras)
        val reveal = extras.getBoolean(EXTRA_REVEAL, false)
        // What Kestrel heard in an owner's recording never leaves the device (section 16.4).
        if (reveal && !file.name.startsWith(GENERATED)) {
            throw HookError("only a generated clip, whose name starts with $GENERATED, may show what Kestrel heard")
        }
        val expected = extras.getString(EXTRA_EXPECTED) ?: throw HookError("add --es expected <text>")
        val kind = extras.getString(EXTRA_KIND) ?: throw HookError("add --es kind with the expected request, or $SKIP")
        requireOff()
        if (!file.isFile) throw HookError("there is no clip ${file.name}")
        val samples = try {
            withContext(Dispatchers.IO) { Wav.read(file) }
        } catch (e: IllegalArgumentException) {
            throw HookError("the clip is not a WAV file of 16 kHz mono 16-bit PCM")
        }
        // The labels of the screen in front, for the A71 session's transcription time on a busy screen; they never leave
        // the device. Otherwise the labels of the line's scene, which scripts\voice-eval.ps1 passes.
        val labels = if (extras.getBoolean(EXTRA_SCREEN_LABELS, false)) {
            graph.screen.value?.let { BoostWordBudget.screenNames(it.snapshots.current().snapshot) }.orEmpty()
        } else {
            extras.getString(EXTRA_LABELS)?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        }
        return evaluationLock.withLock {
            val ready = synchronized(lock) { evaluation as? Evaluation.Ready }
                ?: throw HookError("load the recogniser first with --ez load true, until it answers ready")
            withContext(Dispatchers.Default) { compare(ready, samples, expected, kind, extras, reveal, labels) }
        }
    }

    private fun compare(
        ready: Evaluation.Ready,
        samples: FloatArray,
        expected: String,
        kind: String,
        extras: Bundle,
        reveal: Boolean,
        labels: List<String>,
    ): String {
        // As in Kestrel's pipeline, the recogniser gets only the speech that the detector finds, here from the start of its
        // first utterance to the end of its last one, with the audio that the detector adds before and after them, so
        // that the rest of the clip's silence does not count (section 8.3). The detector starts each clip anew.
        ready.detector.finish()
        val parts = ready.detector.accept(samples) + ready.detector.finish()
        val speech = if (parts.isEmpty()) {
            FloatArray(0)
        } else {
            val end = minOf(parts.last().endSample + parts.last().trail, samples.size.toLong())
            samples.copyOfRange((parts.first().startSample - parts.first().lead).toInt(), end.toInt())
        }
        val recognizer = ready.recognizer
        val before = recognizer.secondDecodings
        val started = SystemClock.elapsedRealtime()
        // A line of the corpus also gets the command vocabulary and its scene's labels as the screen's, as each
        // utterance of Kestrel's voice session does (design spec, section 8.3), unless noLabels leaves them out to show
        // their effect. Without any boost words on request, to show theirs (plan 2C-3's final review, item 3).
        val group = extras.getString(EXTRA_GROUP)
        val words = when {
            extras.getBoolean(EXTRA_NO_BOOST, false) -> emptyList()
            group == null || extras.getBoolean(EXTRA_NO_LABELS, false) -> ready.words
            else -> BoostWordBudget.combine(ready.words, labels, CommandVocabulary.BOOST)
        }
        val heard = if (speech.isEmpty()) "" else recognizer.transcribe(speech, words).text
        val millis = SystemClock.elapsedRealtime() - started
        val twice = recognizer.secondDecodings - before
        val errors = WordErrors.count(expected, heard)
        val read = if (kind == SKIP) {
            "-"
        } else {
            kindOf(
                Requests.read(
                    heard,
                    spotted = extras.getBoolean(EXTRA_SPOTTED, false),
                    afterName = extras.getBoolean(EXTRA_AFTER_NAME, false),
                ),
            )
        }
        val request = when {
            kind == SKIP -> "skipped"
            read == kind -> "ok"
            else -> "wrong"
        }
        // The contacts that the call lines name go only to the contact matcher, never to the recogniser.
        val contacts = extras.getString(EXTRA_CONTACTS)?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: labels
        val answer = "words=${errors.words} substitutions=${errors.substitutions} deletions=${errors.deletions} " +
            "insertions=${errors.insertions} request=$request read=$read millis=$millis seconds=${seconds(speech.size)} " +
            "pss=${Debug.getPss() / 1024} parts=${parts.size} twice=$twice" +
            group?.let { " command=${CorpusVerdict.command(it, expected, heard, labels, contacts)}" }.orEmpty()
        return if (reveal) "$answer heard=$heard" else answer
    }

    /** The name of a request's kind, as corpus\commands.txt writes it. */
    private fun kindOf(request: Request?): String = when (request) {
        null -> "None"
        is Request.Stop -> if (request.named) "StopNamed" else "Stop"
        else -> request::class.simpleName ?: "Unknown"
    }

    private fun seconds(samples: Int) = String.format(Locale.ROOT, "%.1f", samples / SAMPLE_RATE.toFloat())

    private sealed interface Evaluation {
        class Loading(val job: Job) : Evaluation

        class Ready(
            val recognizer: Recognizer,
            val detector: SpeechDetector,
            val words: List<String>,
            val kept: Int,
        ) : Evaluation {
            fun close() {
                recognizer.close()
                detector.close()
            }
        }

        class Failed(val what: String) : Evaluation
    }

    private sealed interface Recording {
        object Running : Recording

        class Ended(val answer: String?, val failure: String?) : Recording
    }

    companion object {
        val COMMANDS = setOf("VOICE_SYNTH", "VOICE_RECORD", "VOICE_EVAL")

        /** The folder of the clips in Kestrel's external files folder. */
        const val FOLDER = "eval"

        /** The start of a generated clip's name; only such a clip may show what Kestrel heard. */
        const val GENERATED = "gen-"

        // Kept between the calls of one corpus run, because loading a model takes seconds; the scripts close them.
        private val lock = Any()
        private var evaluation: Evaluation? = null
        private var recording: Recording? = null
        private val evaluationLock = Mutex()
        private val voiceLock = Mutex()
        private var voice: Synthesizer? = null
        private var voiceChoice: VoiceChoice? = null

        private val CLIP_NAME = Regex("[A-Za-z0-9_-]{1,64}")
        private const val MAX_RECORDING_SECONDS = 15

        // The kind of a line whose reading is not checked, such as a call command (corpus\commands.txt writes '-').
        private const val SKIP = "skip"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_FILE = "file"
        private const val EXTRA_VOICE = "voice"
        private const val EXTRA_SECONDS = "seconds"
        private const val EXTRA_RESULT = "result"
        private const val EXTRA_LOAD = "load"
        private const val EXTRA_CLOSE = "close"
        private const val EXTRA_EXPECTED = "expected"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_SPOTTED = "spotted"
        private const val EXTRA_AFTER_NAME = "afterName"
        private const val EXTRA_REVEAL = "reveal"
        private const val EXTRA_NO_BOOST = "noBoost"
        private const val EXTRA_GROUP = "group"
        private const val EXTRA_LABELS = "labels"
        private const val EXTRA_CONTACTS = "contacts"
        private const val EXTRA_NO_LABELS = "noLabels"
        private const val EXTRA_SCREEN_LABELS = "screenLabels"
    }
}
