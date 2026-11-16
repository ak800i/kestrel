package dev.kestrel.app.listen

import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.app.orchestrator.Work
import dev.kestrel.voice.Keyword
import dev.kestrel.voice.ModelsNotReadyException
import dev.kestrel.voice.listen.Microphone
import dev.kestrel.voice.listen.Recognition
import dev.kestrel.voice.models.Engine
import dev.kestrel.voice.speak.Spoke
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListeningTest {
    private val log = mutableListOf<String>()
    private val shown = mutableListOf<String>()
    private val reports = mutableListOf<Pair<String, Throwable>>()
    private val sessions = FakeSessions()
    private val services = FakeServices()
    private val microphone = FakeMicrophone()
    private val service = Any()

    @Test
    fun theServiceStartsWhenTheModeLeavesOffAndTheSessionOpensOnlyInTheForeground() = runTest {
        val listening = listening()

        listening.send(Event.Toggle)
        runCurrent()
        assertEquals(listOf("start"), log)
        assertFalse(listening.sessionOpen.value)

        listening.onForeground(service)
        runCurrent()

        // Recognition is set before the microphone's first frame.
        assertEquals(listOf("start", "open", "recognition ALL", "microphone on"), log)
        assertSame(microphone, sessions.microphones.single())
        assertTrue(listening.sessionOpen.value)
    }

    @Test
    fun theMicrophoneAndRecognitionFollowTheState() = runTest {
        val listening = listeningWithSession()
        log.clear()

        listening.send(Event.Screen(on = false))
        runCurrent()
        listening.send(Event.Screen(on = true))
        runCurrent()
        listening.send(Event.Pause)
        runCurrent()

        assertEquals(listOf("microphone off", "microphone on", "recognition NAMED"), log)
    }

    @Test
    fun kestrelSaysYesAndTheEndOfTheReplyTurnsRecognitionOnAgain() = runTest {
        val listening = listeningWithSession()
        val session = sessions.opened.single()
        log.clear()

        listening.send(Event.Heard("Kestrel", named = true))
        runCurrent()
        assertEquals(listOf(ListeningTexts.YES), session.said)
        assertTrue(listening.state.value.speaking)

        session.replies.single().complete(Spoke.FINISHED)
        runCurrent()

        assertFalse(listening.state.value.speaking)
        assertEquals(Work.AWAITING_TASK, listening.state.value.work)
        assertEquals(listOf("recognition NONE", "recognition ALL"), log)
        // A reply that was spoken is not shown.
        assertEquals(listOf(ListeningTexts.LISTENING, "Kestrel"), shown)
    }

    @Test
    fun aStopWhileKestrelSpeaksStopsTheReply() = runTest {
        val listening = listeningWithSession()
        listening.send(Event.Heard("Kestrel", named = true))
        runCurrent()
        log.clear()

        listening.send(Event.Spotted(Keyword.STOP))
        runCurrent()

        assertEquals(listOf("stop speaking"), log)
    }

    @Test
    fun aTaskGetsTheStubReplyAtOnce() = runTest {
        val listening = listeningWithSession()

        listening.send(Event.Heard("Kestrel, open the settings.", named = true))
        runCurrent()

        assertEquals(listOf(ListeningTexts.NO_TASKS), sessions.opened.single().said)
        assertEquals(Work.IDLE, listening.state.value.work)
        assertEquals(1, listening.state.value.task)
    }

    @Test
    fun aReplyThatCannotBeSpokenIsShownOnTheBar() = runTest {
        val listening = listeningWithSession()
        val session = sessions.opened.single()
        listening.send(Event.Heard("Kestrel, open the settings.", named = true))
        runCurrent()
        shown.clear()

        session.replies[0].complete(Spoke.REFUSED)
        runCurrent()
        listening.send(Event.Heard("Kestrel", named = true))
        runCurrent()
        session.replies[1].completeExceptionally(IllegalStateException("The voice failed"))
        runCurrent()

        assertEquals(listOf(ListeningTexts.NO_TASKS, "Kestrel", ListeningTexts.YES), shown)
        assertEquals("A reply", reports.single().first)
        assertFalse(listening.state.value.speaking)
    }

    @Test
    fun aReplyWithoutASessionIsEndedByTheOrchestrator() = runTest {
        val listening = listening()
        listening.send(Event.Toggle)
        runCurrent()

        // The service has not reported the foreground yet, so no session is open.
        listening.send(Event.Heard("Kestrel", named = true))
        runCurrent()

        assertEquals("An effect", reports.single().first)
        assertFalse(listening.state.value.speaking)
        assertEquals(Work.AWAITING_TASK, listening.state.value.work)
        // Section 14.6: without a voice, the reply's text goes to the bar.
        assertEquals(ListeningTexts.YES, shown.last())
    }

    @Test
    fun theFeedbackBarShowsModesTranscriptsAndMicBusy() = runTest {
        val listening = listeningWithSession()

        listening.send(Event.Heard("Open the settings.", named = false))
        listening.send(Event.MicBusy(true))
        listening.send(Event.Toggle)
        listening.send(Event.TurnOff)
        runCurrent()

        assertEquals(
            listOf(ListeningTexts.LISTENING, "Open the settings.", ListeningTexts.MIC_BUSY, ListeningTexts.PAUSED, ListeningTexts.OFF),
            shown,
        )
    }

    @Test
    fun offClosesTheSessionAndThenStopsTheService() = runTest {
        val listening = listeningWithSession()
        log.clear()

        listening.send(Event.TurnOff)
        runCurrent()

        // Closing the session stops its microphone first; the service stops only after the close.
        assertEquals(listOf("close", "closed", "stop"), log)
        assertFalse(listening.sessionOpen.value)
        assertEquals(Mode.OFF, listening.state.value.mode)
    }

    @Test
    fun aServiceThatCannotStartTurnsKestrelOffAndSaysWhy() = runTest {
        services.failure = IllegalStateException("Not allowed in the background")
        val listening = listening()

        listening.send(Event.Toggle)
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(listOf("start", "stop"), log)
        // The reason takes the place of "Off", which would replace it at once.
        assertEquals(listOf(ListeningTexts.LISTENING, ListeningTexts.CANNOT_START), shown)
        assertEquals("Starting the listening service", reports.single().first)
    }

    @Test
    fun aServiceThatCannotEnterTheForegroundTurnsKestrelOffAndSaysWhy() = runTest {
        val listening = listening()
        listening.send(Event.Toggle)
        runCurrent()

        listening.onForegroundFailed(SecurityException("No microphone permission"))
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(listOf("start", "stop"), log)
        assertEquals(listOf(ListeningTexts.LISTENING, ListeningTexts.CANNOT_START), shown)
        assertTrue(reports.single().second is SecurityException)
    }

    @Test
    fun missingModelsTurnKestrelOffAndSaySo() = runTest {
        sessions.failure = ModelsNotReadyException(Engine.ASR, emptyList())
        val listening = listening()
        listening.send(Event.Toggle)
        runCurrent()

        listening.onForeground(service)
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(listOf("start", "open", "stop"), log)
        assertEquals(listOf(ListeningTexts.LISTENING, ListeningTexts.MODELS_MISSING), shown)
        assertFalse(listening.sessionOpen.value)
    }

    @Test
    fun aNewStartWaitsForTheCloseInProgress() = runTest {
        val listening = listeningWithSession()
        val closing = CompletableDeferred<Unit>()
        sessions.opened.single().closing = closing
        log.clear()

        listening.send(Event.TurnOff)
        runCurrent()
        listening.send(Event.Toggle)
        runCurrent()
        assertEquals(listOf("close"), log)

        closing.complete(Unit)
        runCurrent()
        assertEquals(listOf("close", "closed", "stop", "start"), log)

        listening.onForeground(Any())
        runCurrent()
        assertEquals(2, sessions.opened.size)
        assertTrue(listening.sessionOpen.value)
    }

    @Test
    fun aSessionThatFinishesOpeningAfterOffIsClosedAtOnce() = runTest {
        val opening = CompletableDeferred<Unit>()
        sessions.opening = opening
        val listening = listening()
        listening.send(Event.Toggle)
        runCurrent()
        listening.onForeground(service)
        runCurrent()

        listening.send(Event.TurnOff)
        runCurrent()
        opening.complete(Unit)
        runCurrent()

        assertEquals(listOf("start", "open", "close", "closed", "stop"), log)
        assertFalse(listening.sessionOpen.value)
    }

    @Test
    fun anUnexpectedEndOfTheServiceTurnsKestrelOff() = runTest {
        val listening = listeningWithSession()
        log.clear()

        listening.onServiceDestroyed(service)
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(listOf("close", "closed", "stop"), log)
    }

    @Test
    fun theEndOfAServiceThatKestrelStoppedChangesNothing() = runTest {
        val listening = listeningWithSession()
        listening.send(Event.TurnOff)
        runCurrent()
        listening.send(Event.Toggle)
        runCurrent()

        // Android destroys the old service only now, while the new one starts.
        listening.onServiceDestroyed(service)
        runCurrent()
        listening.onForeground(Any())
        runCurrent()

        assertEquals(Mode.LISTENING, listening.state.value.mode)
        assertEquals(2, sessions.opened.size)
    }

    @Test
    fun aMicrophoneThatFailsTurnsKestrelOff() = runTest {
        val listening = listeningWithSession()
        log.clear()

        sessions.opened.single().listener.microphoneFailed(IllegalStateException("The microphone failed with error -3"))
        runCurrent()

        assertEquals(listOf("microphone off", "close", "closed", "stop"), log)
        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(ListeningTexts.MICROPHONE_FAILED, shown.last())
        assertEquals("The microphone", reports.single().first)
    }

    @Test
    fun aMicrophoneThatDoesNotStartIsReleasedAndTurnsKestrelOff() = runTest {
        sessions.microphoneFailure = SecurityException("Kestrel may not record audio")
        val listening = listening()
        listening.send(Event.Toggle)
        runCurrent()

        listening.onForeground(service)
        runCurrent()

        assertEquals(
            listOf("start", "open", "recognition ALL", "microphone on", "microphone off", "close", "closed", "stop"),
            log,
        )
        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals("Starting the microphone", reports.single().first)
    }

    @Test
    fun eventsFromAClosedSessionAreIgnored() = runTest {
        val listening = listeningWithSession()
        val old = sessions.opened.single().listener
        listening.send(Event.TurnOff)
        runCurrent()
        listening.send(Event.Toggle)
        runCurrent()

        old.send(Event.Heard("Kestrel, open the settings.", named = true))
        runCurrent()

        assertEquals(0, listening.state.value.task)
    }

    @Test
    fun aServiceThatNobodyAskedForIsStopped() = runTest {
        val listening = listening()

        listening.onForeground(service)
        runCurrent()

        assertEquals(listOf("stop"), log)
        assertEquals(Mode.OFF, listening.state.value.mode)
    }

    @Test
    fun aServiceThatIsStillStartingIsStoppedOnlyOnceItReports() = runTest {
        val listening = listening()
        listening.send(Event.Toggle)
        runCurrent()

        // Turned off before the service called startForeground(): stopping it now would end Kestrel's process.
        listening.send(Event.TurnOff)
        runCurrent()
        assertEquals(listOf("start"), log)

        listening.onForeground(service)
        runCurrent()
        assertEquals(listOf("start", "stop"), log)
    }

    private fun TestScope.listening(): Listening = Listening(
        scope = backgroundScope,
        thread = StandardTestDispatcher(testScheduler),
        sessions = sessions,
        services = services,
        bar = { shown += it },
        report = { what, error -> reports += what to error },
        microphones = { microphone },
    )

    /** Turns listening on, and lets the service report the foreground, so that a session opens. */
    private fun TestScope.listeningWithSession(): Listening {
        val listening = listening()
        listening.send(Event.Toggle)
        runCurrent()
        listening.onForeground(service)
        runCurrent()
        return listening
    }

    private inner class FakeSessions : VoiceSessions {
        val opened = mutableListOf<FakeSession>()
        val microphones = mutableListOf<Microphone>()
        var failure: Exception? = null
        var opening: CompletableDeferred<Unit>? = null
        var microphoneFailure: Exception? = null

        override suspend fun open(microphone: Microphone, listener: SessionListener): Session {
            log += "open"
            microphones += microphone
            opening?.await()
            failure?.let { throw it }
            return FakeSession(listener, microphoneFailure).also { opened += it }
        }
    }

    private inner class FakeSession(val listener: SessionListener, private val microphoneFailure: Exception?) : Session {
        val said = mutableListOf<String>()
        val replies = mutableListOf<CompletableDeferred<Spoke>>()
        var closing: CompletableDeferred<Unit>? = null
        private var current: Recognition? = null

        // Logged only when it changes, so that the log shows what the pipeline would see.
        override var recognition: Recognition
            get() = current ?: Recognition.ALL
            set(value) {
                if (value != current) log += "recognition $value"
                current = value
            }

        override fun startMicrophone() {
            log += "microphone on"
            microphoneFailure?.let { throw it }
        }

        override fun stopMicrophone() {
            log += "microphone off"
        }

        override fun speak(text: String): Deferred<Spoke> {
            said += text
            return CompletableDeferred<Spoke>().also { replies += it }
        }

        override fun stopSpeaking() {
            log += "stop speaking"
        }

        override suspend fun close() {
            log += "close"
            closing?.await()
            log += "closed"
        }
    }

    private inner class FakeServices : ServiceControl {
        var failure: Exception? = null

        override fun start() {
            log += "start"
            failure?.let { throw it }
        }

        override fun stop() {
            log += "stop"
        }
    }

    private class FakeMicrophone : Microphone {
        override val silenced: StateFlow<Boolean> = MutableStateFlow(false)
        override val echoCancelling = false

        override fun start(onFrame: (FloatArray) -> Unit, onError: (Exception) -> Unit) = Unit

        override fun stop() = Unit
    }
}
