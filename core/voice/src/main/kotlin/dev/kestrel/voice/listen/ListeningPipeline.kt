package dev.kestrel.voice.listen

import dev.kestrel.voice.Keyword
import dev.kestrel.voice.KeywordSpotter
import dev.kestrel.voice.Recognizer
import dev.kestrel.voice.SAMPLE_RATE
import dev.kestrel.voice.SpeechDetector
import dev.kestrel.voice.Utterance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/** Which utterances the pipeline transcribes (design spec, sections 8.4 and 8.5). */
enum class Recognition {
    /** Every utterance, while Kestrel listens. */
    ALL,

    /** Only the utterances that start with "Kestrel", while Kestrel is paused. */
    NAMED,

    /** None, while Kestrel speaks. The keyword spotter still listens. */
    NONE,
}

/** What the pipeline heard. */
sealed interface VoiceEvent {
    /**
     * The transcript of an utterance of [seconds] that took [millis] to transcribe; it may be empty. [named] is true when
     * the keyword spotter heard "Kestrel" at its start. The part of an utterance that is left after the pipeline cut away
     * Kestrel's own voice is never named, because the cut removed the part that held the name.
     */
    data class Heard(val text: String, val named: Boolean, val seconds: Float, val millis: Long) : VoiceEvent {
        override fun toString() = "Heard(${text.length} characters, named=$named, seconds=$seconds, millis=$millis)"
    }

    /** The keyword spotter heard [keyword], and it was not Kestrel's own voice. */
    data class Spotted(val keyword: Keyword) : VoiceEvent

    /**
     * The owner started to speak while recognition is on: the speech detector hears speech on a frame for which
     * recognition is not [Recognition.NONE], and on the frame before it heard none or recognition was [Recognition.NONE].
     * It comes again on the first frame on which the detector still hears speech after the pipeline sent an event for an
     * earlier utterance: a transcript, a failure or a drop. The Orchestrator's `Event.Speaking`.
     */
    data object SpeechStarted : VoiceEvent

    /**
     * An utterance ended that recognition would have transcribed, but nothing of it was left to transcribe: it began
     * while Kestrel spoke, and after the cut at the resume less than 0.25 s remained, or the owner's "Kestrel" broke into
     * it (section 8.4).
     */
    data object Dropped : VoiceEvent

    /** Transcribing an utterance failed; the pipeline goes on with the next one. */
    data class Failed(val error: Exception) : VoiceEvent {
        // The message could hold words from the screen or the owner, so only the class is printed.
        override fun toString() = "Failed(${error.javaClass.name})"
    }
}

/**
 * Turns the microphone's frames into transcripts and keyword events (design spec, sections 7.5 and 8.1–8.4). The speech
 * detector and the keyword spotter hear every frame; the endpointer decides when an utterance has ended, and the
 * recogniser transcribes the utterances that [recognition] asks for, one at a time in [recognitionContext], with the
 * boost words that [boostWords] returns at that moment. [isOwnVoice] tells which keyword hits came from Kestrel's own
 * voice. The pipeline does not own the engines: whoever opened them closes them, after [close] and after the last call
 * of [accept] and [pause], because the engines throw when they are used after they were closed. Call [accept] and
 * [pause] from one thread at a time: from the microphone's thread, or after it has stopped.
 */
class ListeningPipeline(
    private val detector: SpeechDetector,
    private val spotter: KeywordSpotter,
    private val recognizer: Recognizer,
    private val boostWords: suspend () -> List<String>,
    private val isOwnVoice: (Keyword) -> Boolean,
    scope: CoroutineScope,
    recognitionContext: CoroutineContext,
) {
    private val output = Channel<VoiceEvent>(Channel.UNLIMITED)
    private val queue = Channel<Speech>(Channel.UNLIMITED)
    private val endpointer = Endpointer()

    // The worker sets it after each event that it sends; the microphone's thread reads and clears it on the next frame.
    private val sent = AtomicBoolean(false)
    private val worker = scope.launch(recognitionContext) {
        for (speech in queue) transcribe(speech)
    }

    // Only the microphone's thread uses these. Positions count samples since the audio started.
    private var position = 0L
    private var detectorStart = 0L
    private var lastRecognition = Recognition.ALL
    private var resumeFrom = 0L
    private var interrupted = false
    private var interruption: Long? = null
    private var hearing = false

    /**
     * What the pipeline heard, in the order in which the pipeline produces the events. A transcript can come after the
     * keyword events of later audio, because transcribing takes time. Collect it once.
     */
    val events: Flow<VoiceEvent> = output.receiveAsFlow()

    /** Which utterances to transcribe from the next frame on. Any thread may set it. */
    @Volatile
    var recognition: Recognition = Recognition.ALL

    /** Feeds one frame of 16 kHz samples. */
    fun accept(frame: FloatArray) {
        val wanted = recognition
        val before = lastRecognition
        if (before == Recognition.NONE && wanted != Recognition.NONE) resume()
        lastRecognition = wanted
        val utterances = detector.accept(frame).map { Utterance(detectorStart + it.startSample, it.samples) }
        val hits = spotter.accept(frame).filterNot { isOwnVoice(it.keyword) }
        position += frame.size
        val names = mutableListOf<Long>()
        for (hit in hits) {
            if (hit.keyword == Keyword.KESTREL) {
                names += position
                // Section 8.4: "Kestrel" while Kestrel speaks interrupts it.
                if (wanted == Recognition.NONE) interruption = position
            }
            output.trySend(VoiceEvent.Spotted(hit.keyword))
        }
        val hears = detector.hearsSpeech
        // An event for an earlier utterance went out since the last frame: the worker's, or a drop below.
        var answered = sent.getAndSet(false)
        for (speech in endpointer.accept(utterances, names, hears, position)) {
            val kept = afterResume(speech)
            if (kept == null) {
                if (transcribes(wanted, speech.named)) {
                    output.trySend(VoiceEvent.Dropped)
                    answered = true
                }
            } else if (transcribes(wanted, kept.named)) {
                queue.trySend(kept)
            }
        }
        // The Orchestrator's Event.Speaking: an event for an earlier utterance may have shortened the wait for a task, so
        // speech that goes on after it counts as a new start.
        if (hears && wanted != Recognition.NONE && (!hearing || before == Recognition.NONE || answered)) {
            output.trySend(VoiceEvent.SpeechStarted)
        }
        hearing = hears
    }

    /**
     * Drops the utterance that the endpointer holds and the audio in progress after the microphone stopped, so that they
     * do not run into the next audio. A transcription that was already queued or running still finishes, and its event
     * still goes out; the Orchestrator ignores transcripts while the screen is off.
     */
    fun pause() {
        detector.finish()
        detectorStart = position
        endpointer.finish()
        interruption = null
        hearing = false
    }

    /**
     * Stops transcribing, drops the utterances that wait and ends [events]. Returns when the recogniser is no longer in
     * use.
     */
    suspend fun close() {
        try {
            queue.cancel()
            worker.cancelAndJoin()
        } finally {
            output.close()
        }
    }

    /**
     * Recognition resumes after Kestrel spoke. What the microphone heard until now is Kestrel's own voice. When the owner
     * broke in with "Kestrel", an utterance that starts with the name is the owner's and counts in full; one that began
     * earlier began with Kestrel's voice, which also runs under and after the name, so it is dropped (section 8.4).
     */
    private fun resume() {
        val hit = interruption
        interruption = null
        interrupted = hit != null && position - hit <= INTERRUPTION
        resumeFrom = if (hit != null && interrupted) hit - NAME_LENGTH else position
    }

    /** [speech] without its part before [resumeFrom], or null when too little is left or the owner broke into it. */
    private fun afterResume(speech: Speech): Speech? = when {
        speech.startSample >= resumeFrom -> speech
        interrupted || speech.endSample - resumeFrom < SHORTEST -> null
        else -> Speech(
            resumeFrom,
            speech.samples.copyOfRange((resumeFrom - speech.startSample).toInt(), speech.samples.size),
            named = false,
        )
    }

    /** Whether recognition [wanted] transcribes an utterance that is [named], or not. */
    private fun transcribes(wanted: Recognition, named: Boolean): Boolean = when (wanted) {
        Recognition.ALL -> true
        Recognition.NAMED -> named
        Recognition.NONE -> false
    }

    private suspend fun transcribe(speech: Speech) {
        val event = try {
            val words = boostWords()
            val started = System.nanoTime()
            val transcript = recognizer.transcribe(speech.samples, words)
            VoiceEvent.Heard(transcript.text, speech.named, speech.seconds, (System.nanoTime() - started) / 1_000_000)
        } catch (e: Exception) {
            // A cancellation that is not the worker's own, such as a timeout inside boostWords, is a failure like any
            // other.
            currentCoroutineContext().ensureActive()
            VoiceEvent.Failed(e)
        }
        output.trySend(event)
        sent.set(true)
    }

    private companion object {
        // A "Kestrel" that interrupts Kestrel's speech counts when recognition resumes at most 3 s later.
        const val INTERRUPTION = 3L * SAMPLE_RATE

        // An utterance that starts with the name that broke in starts at most this long before the hit: "Kestrel" takes
        // about half a second, and the spotter reports it a little after it ends.
        const val NAME_LENGTH = 3L * SAMPLE_RATE / 2

        // A rest shorter than the speech detector's own minimum of 0.25 s is dropped.
        const val SHORTEST = SAMPLE_RATE / 4L
    }
}
