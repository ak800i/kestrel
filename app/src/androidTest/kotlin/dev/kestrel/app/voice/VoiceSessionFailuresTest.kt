package dev.kestrel.app.voice

import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.listen.LoadedVoice
import dev.kestrel.app.listen.SessionListener
import dev.kestrel.app.listen.VoiceSession
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.commands.CommandVocabulary
import dev.kestrel.voice.KeywordHit
import dev.kestrel.voice.KeywordSpotter
import dev.kestrel.voice.ModelsNotReadyException
import dev.kestrel.voice.Recognizer
import dev.kestrel.voice.SAMPLE_RATE
import dev.kestrel.voice.SpeechDetector
import dev.kestrel.voice.Synthesizer
import dev.kestrel.voice.Transcript
import dev.kestrel.voice.Utterance
import dev.kestrel.voice.VoiceOptions
import dev.kestrel.voice.listen.Microphone
import dev.kestrel.voice.listen.Recognition
import dev.kestrel.voice.models.Engine
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The voice session's error paths, with fake engines and a microphone that the test feeds by hand, so that no model is
 * needed. The fake engines throw after they were closed, as Kestrel's engines do.
 */
class VoiceSessionFailuresTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val closes = CopyOnWriteArrayList<String>()
    private val detector = FakeDetector(closes)
    private val spotter = FakeSpotter(closes)
    private val recognizer = FakeRecognizer(closes)
    private val synthesizer = FakeSynthesizer(closes)
    private val microphone = ManualMicrophone(closes)
    private val listener = RecordingListener()

    @Test
    fun droppedAndFailedUtterancesReachTheListenerAsNothingHeard() = runBlocking {
        val session = session()
        try {
            session.recognition = Recognition.NONE
            session.startMicrophone()
            microphone.feed(1f)
            session.recognition = Recognition.ALL
            microphone.feed(0.6f)
            // From 0.3 s to 0.8 s, before recognition resumed at 1 s: Kestrel's own voice, so the pipeline drops it.
            detector.next.add(Utterance(seconds(0.3f), FloatArray(seconds(0.5f).toInt())))
            microphone.feed(0.02f)
            // Not an empty transcript, which would count towards the hint of section 14.6.
            assertEquals(Event.NothingHeard, listener.await { it == Event.NothingHeard })
            assertEquals(emptyList<Exception>(), listener.failures.toList())

            recognizer.fails = true
            detector.next.add(Utterance(seconds(1.1f), FloatArray(seconds(0.5f).toInt())))
            microphone.feed(0.02f)
            assertEquals(Event.NothingHeard, listener.await { it == Event.NothingHeard })
            assertEquals(1, listener.failures.size)
            assertTrue(listener.failures.single() is FakeFailure)
        } finally {
            session.close()
        }
    }

    @Test
    fun closeRunsEveryStepWhenOneFailsAndThrowsTheFirstFailure() = runBlocking {
        val session = session()
        session.startMicrophone()
        val first = FakeFailure()
        val second = FakeFailure()
        microphone.stopFailure = first
        detector.closeFailure = second

        val thrown = runCatching { session.close() }.exceptionOrNull()

        assertEquals(listOf("microphone", "recognizer", "spotter", "detector", "synthesizer"), closes.toList())
        assertSame(first, thrown)
        assertEquals(listOf<Throwable>(second), thrown!!.suppressed.toList())
    }

    @Test
    fun aClosedSessionIgnoresASecondCloseAndDoesNotStartTheMicrophone() = runBlocking {
        val session = session()
        session.close()

        // The pipeline's pause would reach the closed speech detector, which throws.
        session.close()
        session.stopMicrophone()
        assertThrows(IllegalStateException::class.java) { session.startMicrophone() }
        assertEquals(0, microphone.starts)
    }

    @Test
    fun noEventReachesTheListenerAfterCloseReturns() = runBlocking {
        val session = session()
        try {
            session.startMicrophone()
            listener.slowHeard = true
            detector.next.add(Utterance(0, FloatArray(seconds(0.5f).toInt())))
            microphone.feed(0.02f)
            assertTrue("The transcript did not go out", listener.sending.await(10, TimeUnit.SECONDS))
        } finally {
            session.close()
        }
        // The listener was still taking the transcript when close() began.
        // The transcript also carries its length and the end of its speech, which the A71 session's counts read.
        val heard = listener.events.filterIsInstance<Event.Heard>().map { it.text to it.named }
        assertEquals(listOf(TEXT to false), heard)
    }

    @Test
    fun theSessionsBoostWordsReachTheRecognizer() = runBlocking {
        val session = VoiceSession.open(
            context, VoiceTests.graph.voice, microphone, listener,
            boostWords = { listOf("Kestrel", "Viber") },
            screenWords = { listOf("Send later") },
            loadSpeechDetector = { detector },
            loadKeywordSpotter = { spotter },
            loadVoice = { LoadedVoice(synthesizer, VoiceOptions()) },
            loadRecognizer = { recognizer },
        )
        try {
            session.recognition = Recognition.ALL
            session.startMicrophone()
            detector.next.add(Utterance(0, FloatArray(seconds(0.5f).toInt())))
            microphone.feed(0.02f)
            listener.await { it is Event.Heard }

            // Each utterance: the name, the screen's labels, the command vocabulary, then the session's app names.
            assertEquals(listOf(listOf("Kestrel", "Send later") + CommandVocabulary.BOOST + "Viber"), recognizer.boostWords.toList())
        } finally {
            session.close()
        }
    }

    @Test
    fun openAsksForTheBoostWordsAfterTheEnginesHaveLoaded() = runBlocking {
        val calls = CopyOnWriteArrayList<String>()

        val session = VoiceSession.open(
            context, VoiceTests.graph.voice, microphone, listener,
            boostWords = { calls += "words"; listOf("Kestrel") },
            loadSpeechDetector = { calls += "detector"; detector },
            loadKeywordSpotter = { calls += "spotter"; spotter },
            loadVoice = { calls += "synthesizer"; LoadedVoice(synthesizer, VoiceOptions()) },
            loadRecognizer = { calls += "recognizer"; recognizer },
        )
        session.close()

        // The caller reads the words while the engines load, so open must not wait for them before a load.
        assertEquals(listOf("detector", "spotter", "synthesizer", "recognizer", "words"), calls.toList())
    }

    @Test
    fun openClosesTheLoadedEnginesWhenALaterLoadFails() = runBlocking {
        val missing = ModelsNotReadyException(Engine.TTS, emptyList())
        val closing = FakeFailure()
        spotter.closeFailure = closing
        var recognizerLoaded = false

        val thrown = runCatching {
            VoiceSession.open(
                context, VoiceTests.graph.voice, microphone, listener,
                loadSpeechDetector = { detector },
                loadKeywordSpotter = { spotter },
                loadVoice = { throw missing },
                loadRecognizer = { recognizerLoaded = true; recognizer },
            )
        }.exceptionOrNull()

        assertSame(missing, thrown)
        assertEquals(listOf("spotter", "detector"), closes.toList())
        assertEquals(listOf<Throwable>(closing), missing.suppressed.toList())
        // The recogniser, with its 700 MB, loads last.
        assertFalse(recognizerLoaded)
    }

    @Test
    fun aStartFromBeforeRecognitionResumedGoesToNoTranscript() = runBlocking {
        val session = session()
        try {
            // Paused recognition skips an utterance without the name, which then ends without any event.
            session.recognition = Recognition.NAMED
            session.startMicrophone()
            detector.hearsSpeech = true
            microphone.feed(0.02f)
            listener.await { it == Event.Speaking }
            detector.hearsSpeech = false
            microphone.feed(0.02f)

            // The setter drops that start only when it compares the recognition from before its write.
            session.recognition = Recognition.ALL
            detector.next.add(Utterance(0, FloatArray(seconds(0.5f).toInt())))
            microphone.feed(0.02f)

            val heard = listener.await { it is Event.Heard } as Event.Heard
            assertNull(heard.spokeAt)
        } finally {
            session.close()
        }
    }

    @Test
    fun speechThatTheMicrophoneHearsAsItStartsKeepsItsStart() = runBlocking {
        val session = session()
        try {
            // The recording delivers speech before start() returns, so the session must drop old starts before it.
            microphone.onStart = {
                detector.hearsSpeech = true
                microphone.feed(0.02f)
                runBlocking { listener.await { it == Event.Speaking } }
            }
            session.startMicrophone()
            detector.hearsSpeech = false
            detector.next.add(Utterance(0, FloatArray(seconds(0.5f).toInt())))
            microphone.feed(0.02f)

            val heard = listener.await { it is Event.Heard } as Event.Heard
            assertNotNull(heard.spokeAt)
        } finally {
            session.close()
        }
    }

    private fun session() = VoiceSession(context, detector, spotter, recognizer, synthesizer, microphone, listener)

    private fun seconds(value: Float): Long = (value * SAMPLE_RATE).toLong()

    private class FakeFailure : IllegalStateException("A fake failure")

    /** Records its close in [closes], then throws [closeFailure] if there is one. */
    private abstract class FakeEngine(
        private val name: String,
        private val closes: MutableList<String>,
    ) : AutoCloseable {
        @Volatile
        var closed = false

        @Volatile
        var closeFailure: Exception? = null

        override fun close() {
            closed = true
            closes += name
            closeFailure?.let { throw it }
        }

        fun checkOpen() = check(!closed) { "The fake $name is closed" }
    }

    /** Hears speech while [hearsSpeech], and returns the utterances in [next], one on each frame. */
    private class FakeDetector(closes: MutableList<String>) : FakeEngine("detector", closes), SpeechDetector {
        val next = ConcurrentLinkedQueue<Utterance>()

        override fun accept(samples: FloatArray): List<Utterance> {
            checkOpen()
            return listOfNotNull(next.poll())
        }

        override fun finish(): List<Utterance> {
            checkOpen()
            return emptyList()
        }

        @Volatile
        override var hearsSpeech = false
    }

    private class FakeSpotter(closes: MutableList<String>) : FakeEngine("spotter", closes), KeywordSpotter {
        override fun accept(samples: FloatArray): List<KeywordHit> {
            checkOpen()
            return emptyList()
        }
    }

    /** Hears [TEXT], or throws a [FakeFailure] while [fails]. */
    private class FakeRecognizer(closes: MutableList<String>) : FakeEngine("recognizer", closes), Recognizer {
        @Volatile
        var fails = false

        /** The boost words of each call of [transcribe]. */
        val boostWords = CopyOnWriteArrayList<List<String>>()

        override fun transcribe(samples: FloatArray, boostWords: List<String>): Transcript {
            checkOpen()
            this.boostWords += boostWords
            if (fails) throw FakeFailure()
            return Transcript(TEXT, pieces = 1, lastWordEnd = 0.5f)
        }
    }

    private class FakeSynthesizer(closes: MutableList<String>) : FakeEngine("synthesizer", closes), Synthesizer {
        override val sampleRate = 22_050

        override fun synthesize(text: String): FloatArray {
            checkOpen()
            return FloatArray(0)
        }
    }

    /** A microphone whose frames the test delivers on its own thread with [feed]. Its stop is recorded in [closes]. */
    private class ManualMicrophone(private val closes: MutableList<String>) : Microphone {
        override val silenced = MutableStateFlow(false)
        override val echoCancelling = false
        var starts = 0
        var stopFailure: Exception? = null

        /** Runs at the end of each [start], as a recording that delivers its first frames before start() returns. */
        var onStart: (() -> Unit)? = null
        private var onFrame: ((FloatArray) -> Unit)? = null

        override fun start(onFrame: (FloatArray) -> Unit, onError: (Exception) -> Unit) {
            starts++
            this.onFrame = onFrame
            onStart?.invoke()
        }

        override fun stop() {
            onFrame = null
            closes += "microphone"
            stopFailure?.let { throw it }
        }

        /** Delivers [seconds] of silence in 20 ms frames. */
        fun feed(seconds: Float) {
            val deliver = checkNotNull(onFrame) { "The microphone does not record" }
            repeat((seconds * 50).toInt().coerceAtLeast(1)) { deliver(FloatArray(VoiceTests.FRAME)) }
        }
    }

    /** Records what a session reports. While [slowHeard], it takes a second for a transcript. */
    private class RecordingListener : SessionListener {
        val events = CopyOnWriteArrayList<Event>()
        val failures = CopyOnWriteArrayList<Exception>()
        val sending = CountDownLatch(1)

        @Volatile
        var slowHeard = false
        private val arrived = Channel<Event>(Channel.UNLIMITED)

        override fun send(event: Event) {
            if (event is Event.Heard && slowHeard) {
                sending.countDown()
                Thread.sleep(1_000)
            }
            events += event
            arrived.trySend(event)
        }

        override fun microphoneFailed(error: Exception) {
            failures += error
        }

        override fun transcriptionFailed(error: Exception) {
            failures += error
        }

        /** Waits up to 10 s for the next event that [matches]. */
        suspend fun await(matches: (Event) -> Boolean): Event = withTimeout(10_000) {
            var event = arrived.receive()
            while (!matches(event)) event = arrived.receive()
            event
        }
    }

    private companion object {
        const val TEXT = "open the settings"
    }
}
