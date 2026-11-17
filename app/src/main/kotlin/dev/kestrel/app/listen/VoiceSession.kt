package dev.kestrel.app.listen

import android.content.Context
import android.os.SystemClock
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.voice.KeywordSpotter
import dev.kestrel.voice.Recognizer
import dev.kestrel.voice.SpeechDetector
import dev.kestrel.voice.Synthesizer
import dev.kestrel.voice.VoiceEngines
import dev.kestrel.voice.listen.ListeningPipeline
import dev.kestrel.voice.listen.Microphone
import dev.kestrel.voice.listen.Recognition
import dev.kestrel.voice.listen.VoiceEvent
import dev.kestrel.voice.speak.EchoFilter
import dev.kestrel.voice.speak.Spoke
import dev.kestrel.voice.speak.VoicePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Kestrel's voice session (design spec, sections 7.2 and 8.1–8.4). It owns the speech engines, [microphone], the
 * listening pipeline, the echo filter and the voice player, and turns what the pipeline hears into the Orchestrator's
 * events for [listener]. The microphone's thread feeds the pipeline and reports its errors to [listener];
 * transcription runs one utterance at a time on [Dispatchers.Default], and the events go out from coroutines of the
 * session. [words] are the boost words of every utterance of the session (section 8.3).
 */
internal class VoiceSession(
    context: Context,
    private val detector: SpeechDetector,
    private val spotter: KeywordSpotter,
    private val recognizer: Recognizer,
    private val synthesizer: Synthesizer,
    private val microphone: Microphone,
    private val listener: SessionListener,
    private val words: List<String> = listOf(BoostWordSource.NAME),
) : Session {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closed = AtomicBoolean(false)
    private val player = VoicePlayer(context, synthesizer)
    private val echo = EchoFilter()
    private val pipeline = ListeningPipeline(
        detector = detector,
        spotter = spotter,
        recognizer = recognizer,
        boostWords = { words },
        // The keyword spotter hears Kestrel's own voice too; the echo filter knows what Kestrel said and when (8.4).
        isOwnVoice = { keyword -> echo.isOwnVoice(keyword, SystemClock.elapsedRealtime(), player.recent()) },
        scope = scope,
        recognitionContext = Dispatchers.Default.limitedParallelism(1),
    )

    init {
        // Collected once, from the start, so that no event is lost.
        scope.launch { pipeline.events.collect { forward(it) } }
        scope.launch { microphone.silenced.collect { listener.send(Event.MicBusy(it)) } }
    }

    override var recognition: Recognition
        get() = pipeline.recognition
        set(value) {
            pipeline.recognition = value
        }

    override fun startMicrophone() {
        // A frame would reach a closed engine, and the recording would stay open with nobody to stop it.
        check(!closed.get()) { "The session is closed" }
        microphone.start(onFrame = pipeline::accept, onError = listener::microphoneFailed)
    }

    override fun stopMicrophone() {
        microphone.stop()
        // Only after the microphone's thread has ended, because the pipeline serves one thread at a time. After close()
        // the speech detector is closed, and the pipeline holds nothing.
        if (!closed.get()) pipeline.pause()
    }

    override fun speak(text: String): Deferred<Spoke> = player.speak(text)

    override fun stopSpeaking() = player.stop()

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        val errors = mutableListOf<Exception>()
        // A close that stopped half-way would leave the engines open, with about 1 GB of native memory.
        withContext(NonCancellable) {
            // The microphone first: a recording whose reader stopped stays open, and the engines may close only after the
            // last frame (final review of plan 2B, section 2).
            errors.attempt { microphone.stop() }
            errors.attempt { pipeline.pause() }
            errors.attempt { pipeline.close() }
            errors.attempt { player.close() }
            // An engine waits for a call in progress when it closes, so the engines close on a thread that may wait.
            withContext(Dispatchers.IO) {
                errors.attempt { recognizer.close() }
                errors.attempt { spotter.close() }
                errors.attempt { detector.close() }
                errors.attempt { synthesizer.close() }
            }
            // Waits for an event that is going out, so that nothing reaches the listener after close() returns.
            scope.coroutineContext.job.cancelAndJoin()
        }
        val first = errors.firstOrNull() ?: return
        errors.drop(1).forEach(first::addSuppressed)
        throw first
    }

    private fun forward(event: VoiceEvent) {
        when (event) {
            is VoiceEvent.Heard -> listener.send(Event.Heard(event.text, event.named))
            is VoiceEvent.Spotted -> listener.send(Event.Spotted(event.keyword))
            VoiceEvent.SpeechStarted -> listener.send(Event.Speaking)
            // An utterance without a transcript gives the owner the whole wait for a task again, but it is not an empty
            // transcript, which counts towards the hint of section 14.6 (Rules, Event.NothingHeard).
            VoiceEvent.Dropped -> listener.send(Event.NothingHeard)
            is VoiceEvent.Failed -> {
                listener.transcriptionFailed(event.error)
                listener.send(Event.NothingHeard)
            }
        }
    }

    companion object {
        /**
         * Loads the engines and opens a session (section 8.2): the speech detector, the keyword spotter, the voice that
         * [loadSynthesizer] loads (AppGraph's loads the owner's choice), and the recogniser last, because it holds about
         * 700 MB: a missing model of another
         * engine then fails before it loads. Takes several seconds. Throws `ModelsNotReadyException` when a model is
         * missing or damaged, after closing the engines that it had loaded; it closes them also when a load fails
         * otherwise or is cancelled. It asks [boostWords] once, after the recogniser has loaded and inside the same clean-up,
         * so that the caller can read the words while the engines load, and a cancellation while it waits still closes the
         * engines. The session keeps the words, so that a change of the installed apps counts from the next time that
         * Kestrel turns on. The other loaders are parameters only for the tests.
         */
        suspend fun open(
            context: Context,
            engines: VoiceEngines,
            microphone: Microphone,
            listener: SessionListener,
            boostWords: suspend () -> List<String> = { listOf(BoostWordSource.NAME) },
            loadSpeechDetector: suspend () -> SpeechDetector = { engines.speechDetector() },
            loadKeywordSpotter: suspend () -> KeywordSpotter = { engines.keywordSpotter() },
            loadSynthesizer: suspend () -> Synthesizer = { engines.synthesizer() },
            loadRecognizer: suspend () -> Recognizer = { engines.recognizer() },
        ): VoiceSession {
            val loaded = mutableListOf<AutoCloseable>()
            try {
                val detector = loadSpeechDetector().also { loaded += it }
                val spotter = loadKeywordSpotter().also { loaded += it }
                val synthesizer = loadSynthesizer().also { loaded += it }
                val recognizer = loadRecognizer().also { loaded += it }
                val words = boostWords()
                return VoiceSession(context, detector, spotter, recognizer, synthesizer, microphone, listener, words)
            } catch (e: Throwable) {
                withContext(NonCancellable + Dispatchers.IO) {
                    for (engine in loaded.asReversed()) {
                        try {
                            engine.close()
                        } catch (closing: Exception) {
                            e.addSuppressed(closing)
                        }
                    }
                }
                throw e
            }
        }
    }
}

/** Runs [step], and keeps its exception instead of throwing it, so that the next steps still run. */
private suspend fun MutableList<Exception>.attempt(step: suspend () -> Unit) {
    try {
        step()
    } catch (e: Exception) {
        add(e)
    }
}
