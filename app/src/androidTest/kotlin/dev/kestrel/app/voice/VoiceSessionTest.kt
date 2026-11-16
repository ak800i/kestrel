package dev.kestrel.app.voice

import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.debug.ClipMicrophone
import dev.kestrel.app.listen.SessionListener
import dev.kestrel.app.listen.VoiceSession
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.voice.Keyword
import dev.kestrel.voice.listen.Recognition
import dev.kestrel.voice.models.Engine
import dev.kestrel.voice.speak.Spoke
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** The voice session with the real engines, on Kestrel's own voice played through a stand-in microphone (section 8). */
class VoiceSessionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val clip = ClipMicrophone()
    private val listener = RecordingListener()

    @Before
    fun models() = VoiceTests.assumeModels(Engine.VAD, Engine.KWS, Engine.ASR, Engine.TTS)

    @Test
    fun theSessionTurnsSpeechIntoTheOrchestratorsEvents() = runBlocking {
        val session = VoiceSession.open(context, VoiceTests.graph.voice, clip, listener)
        try {
            session.recognition = Recognition.ALL
            // Queued first, so that the clip starts on the microphone's first frame in every run.
            clip.play(VoiceTests.speech("Kestrel, open the settings."))
            session.startMicrophone()
            val heard = listener.await { it is Event.Heard } as Event.Heard
            clip.silence(true)
            listener.await { it == Event.MicBusy(true) }
            session.stopMicrophone()

            assertTrue(heard.named)
            assertTrue(heard.text.lowercase().contains("open the settings"))
            val events = listener.events.toList()
            assertTrue("No Spotted(KESTREL)", Event.Spotted(Keyword.KESTREL) in events)
            assertTrue("Speaking must come before the transcript", events.indexOf(Event.Speaking) in 0 until events.indexOf(heard))
            assertEquals(emptyList<Exception>(), listener.errors.toList())
        } finally {
            session.close()
        }
        Unit
    }

    @Test
    fun closeStopsTheMicrophoneBeforeTheEnginesAndClosesThemAll() = runBlocking {
        val voice = VoiceTests.graph.voice
        val loaded = mutableListOf<AutoCloseable>()
        var session: VoiceSession? = null
        var closed = false
        try {
            val detector = voice.speechDetector().also { loaded += it }
            val spotter = voice.keywordSpotter().also { loaded += it }
            val recognizer = voice.recognizer().also { loaded += it }
            val synthesizer = voice.synthesizer().also { loaded += it }
            val open = VoiceSession(context, detector, spotter, recognizer, synthesizer, clip, listener)
            session = open
            open.recognition = Recognition.ALL
            clip.play(VoiceTests.speech("Open the settings."))
            open.startMicrophone()
            // The microphone still delivers speech when the session closes. A frame that reached a closed engine would
            // make the pipeline throw, and the microphone would report it as an error.
            delay(1_000)

            open.close()
            closed = true

            assertFalse("The microphone still records", clip.recording)
            assertEquals(emptyList<Exception>(), listener.errors.toList())
            assertThrows(IllegalStateException::class.java) { detector.accept(FloatArray(VoiceTests.FRAME)) }
            assertThrows(IllegalStateException::class.java) { spotter.accept(FloatArray(VoiceTests.FRAME)) }
            assertThrows(IllegalStateException::class.java) { recognizer.transcribe(FloatArray(VoiceTests.FRAME)) }
            assertThrows(IllegalStateException::class.java) { synthesizer.synthesize("Hello.") }
            // The player closed too, so a reply after the close is stopped without the voice.
            assertEquals(Spoke.STOPPED, withTimeout(10_000) { open.speak("Hello.").await() })
        } finally {
            if (!closed) {
                val open = session
                if (open != null) open.close() else loaded.forEach { it.close() }
            }
        }
        Unit
    }

    /** Records what a session reports, from any thread. */
    private class RecordingListener : SessionListener {
        val events = CopyOnWriteArrayList<Event>()
        val errors = CopyOnWriteArrayList<Exception>()
        private val arrived = Channel<Event>(Channel.UNLIMITED)

        override fun send(event: Event) {
            events += event
            arrived.trySend(event)
        }

        override fun microphoneFailed(error: Exception) {
            errors += error
        }

        override fun transcriptionFailed(error: Exception) {
            errors += error
        }

        /** Waits up to 30 s for the next event that [matches]. */
        suspend fun await(matches: (Event) -> Boolean): Event = withTimeout(30_000) {
            var event = arrived.receive()
            while (!matches(event)) event = arrived.receive()
            event
        }
    }
}
