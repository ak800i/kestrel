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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ListeningTest {
    private val log = mutableListOf<String>()
    private val shown = mutableListOf<String>()
    private val reports = mutableListOf<Pair<String, Throwable>>()
    private val sessions = FakeSessions()
    private val services = FakeServices()
    private val microphone = FakeMicrophone()
    private val service = Any()
    private var settingsFailure: Exception? = null
    private val memory = FakeMemory()

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
        assertEquals(listOf("recognition NONE", "recognition ANSWER"), log)
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
    fun kestrelOpenSettingsOpensTheSettingsApp() = runTest {
        val listening = listeningWithSession()
        log.clear()

        listening.send(Event.Heard("Kestrel, open settings.", named = true))
        runCurrent()

        assertEquals(listOf("open settings"), log)
        assertEquals(Mode.LISTENING, listening.state.value.mode)
    }

    @Test
    fun settingsThatCannotOpenAreReportedAndShown() = runTest {
        settingsFailure = IllegalStateException("No activity")
        val listening = listeningWithSession()

        listening.send(Event.Heard("Kestrel settings", named = true))
        runCurrent()

        assertEquals("Opening the settings", reports.single().first)
        assertEquals(ListeningTexts.CANNOT_OPEN_SETTINGS, shown.last())
        assertEquals(Mode.LISTENING, listening.state.value.mode)
    }

    @Test
    fun theModeIsStoredWhenItChanges() = runTest {
        val listening = listeningWithSession()
        listening.send(Event.Toggle)
        runCurrent()
        listening.send(Event.TurnOff)
        runCurrent()

        // Every process starts off, and that Off is not stored. Listening counts once the session is open.
        assertEquals(listOf(Mode.LISTENING, Mode.PAUSED, Mode.OFF), memory.writes)
    }

    @Test
    fun aStartThatFailsStoresOff() = runTest {
        services.failure = IllegalStateException("Not allowed in the background")
        val listening = listening()

        listening.send(Event.Toggle)
        runCurrent()

        // Listening was never stored, because no voice session opened; the Off is a change and is stored.
        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(listOf(Mode.OFF), memory.writes)
    }

    @Test
    fun anOffReplacesAModeThatAnEarlierProcessStored() = runTest {
        // An earlier process ended while Kestrel listened, and no restore has reset the stored mode.
        memory.stored = Mode.LISTENING
        val listening = listening()
        listening.send(Event.Toggle)
        runCurrent()

        // Off before the voice session opened.
        listening.send(Event.TurnOff)
        runCurrent()

        assertEquals(Mode.OFF, memory.stored)
    }

    @Test
    fun theAccessibilityServiceBringsListeningBack() = runTest {
        memory.stored = Mode.LISTENING
        val listening = listening()

        listening.onAccessibilityServiceConnected()
        runCurrent()

        assertEquals(Mode.LISTENING, listening.state.value.mode)
        assertEquals(listOf("start"), log)
    }

    @Test
    fun theAccessibilityServiceBringsThePauseBack() = runTest {
        memory.stored = Mode.PAUSED
        val listening = listening()

        listening.onAccessibilityServiceConnected()
        runCurrent()

        assertEquals(Mode.PAUSED, listening.state.value.mode)
        assertEquals(listOf("start"), log)
    }

    @Test
    fun theStoredModeIsReadOncePerProcess() = runTest {
        memory.stored = Mode.LISTENING
        val listening = listening()
        listening.onAccessibilityServiceConnected()
        runCurrent()
        listening.send(Event.TurnOff)
        runCurrent()

        // The service connects again, for example after the owner turned it off and on in Android's settings.
        memory.stored = Mode.LISTENING
        listening.onAccessibilityServiceConnected()
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(1, memory.reads)
    }

    @Test
    fun aStoredOffStartsNothing() = runTest {
        val listening = listening()

        listening.onAccessibilityServiceConnected()
        runCurrent()

        assertEquals(1, memory.reads)
        assertEquals(Mode.OFF, listening.state.value.mode)
        assertTrue(log.isEmpty())
    }

    @Test
    fun aRestoreStoresOffBeforeItStarts() = runTest {
        memory.stored = Mode.LISTENING
        val listening = listening()

        listening.onAccessibilityServiceConnected()
        runCurrent()

        // If this start ended Kestrel's process, the next start of the process would find Off.
        assertEquals(listOf(Mode.OFF), memory.writes)
        assertEquals(listOf("start"), log)

        listening.onForeground(service)
        runCurrent()

        assertEquals(listOf(Mode.OFF, Mode.LISTENING), memory.writes)
    }

    @Test
    fun aRestoreStartsOnlyOnceItsOffIsStored() = runTest {
        memory.stored = Mode.LISTENING
        memory.writing = CompletableDeferred()
        val listening = listening()

        listening.onAccessibilityServiceConnected()
        runCurrent()

        assertTrue(log.isEmpty())

        memory.writing?.complete(Unit)
        runCurrent()

        assertEquals(listOf("start"), log)
    }

    @Test
    fun aRestoreWhoseOffCannotBeStoredStartsNothing() = runTest {
        memory.stored = Mode.LISTENING
        memory.writeFailure = IOException("The disk failed")
        val listening = listening()

        listening.onAccessibilityServiceConnected()
        runCurrent()

        // A start now could end the process again at every start, because Listening would stay stored.
        assertEquals(Mode.OFF, listening.state.value.mode)
        assertTrue(log.isEmpty())
        assertEquals("Storing the listening mode", reports.single().first)
    }

    @Test
    fun aReadThatFailedIsNotTriedAgainInTheProcess() = runTest {
        memory.readFailure = IOException("The disk failed")
        val listening = listening()
        listening.onAccessibilityServiceConnected()
        runCurrent()

        // The service connects again, for example after the owner turned it off and on in Android's settings.
        memory.readFailure = null
        memory.stored = Mode.LISTENING
        listening.onAccessibilityServiceConnected()
        runCurrent()

        assertEquals(1, memory.reads)
        assertEquals(Mode.OFF, listening.state.value.mode)
        assertTrue(log.isEmpty())
    }

    @Test
    fun nothingIsRestoredOnceKestrelHasBeenOn() = runTest {
        memory.stored = Mode.PAUSED
        val listening = listening()
        listening.send(Event.Toggle)
        runCurrent()
        listening.send(Event.TurnOff)
        runCurrent()

        listening.onAccessibilityServiceConnected()
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(0, memory.reads)
    }

    @Test
    fun theOwnersChoiceDuringTheReadIsKept() = runTest {
        memory.stored = Mode.LISTENING
        memory.reading = CompletableDeferred()
        val listening = listening()
        listening.onAccessibilityServiceConnected()
        runCurrent()

        // While the stored mode is read, the owner turns Kestrel on and off again.
        listening.send(Event.Toggle)
        runCurrent()
        listening.send(Event.TurnOff)
        runCurrent()
        memory.reading?.complete(Unit)
        runCurrent()

        // Only the owner's Off was stored; the restore wrote nothing and started nothing.
        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(listOf(Mode.OFF), memory.writes)
    }

    @Test
    fun anOnAndOffInOneRunDuringTheReadIsKept() = runTest {
        memory.stored = Mode.LISTENING
        memory.reading = CompletableDeferred()
        val listening = listening()
        listening.onAccessibilityServiceConnected()
        runCurrent()

        // Both wait in the channel, so the Orchestrator handles them in one run, and the state's collector sees neither.
        listening.send(Event.Toggle)
        listening.send(Event.TurnOff)
        runCurrent()
        memory.reading?.complete(Unit)
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(Mode.OFF, memory.stored)
    }

    @Test
    fun aRestoreThatTheOffHookSkippedStartsNothing() = runTest {
        memory.stored = Mode.LISTENING
        memory.reading = CompletableDeferred()
        val listening = listening()
        listening.onAccessibilityServiceConnected()
        runCurrent()

        listening.skipRestore()
        memory.reading?.complete(Unit)
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertTrue(log.isEmpty())
    }

    @Test
    fun aStoredModeThatCannotBeReadIsReported() = runTest {
        memory.readFailure = IOException("The disk failed")
        val listening = listening()

        listening.onAccessibilityServiceConnected()
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals("Reading the listening mode", reports.single().first)
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
    fun aNewStartAsksForTheServiceAtOnceAndOpensItsSessionAfterTheClose() = runTest {
        val listening = listeningWithSession()
        val closing = CompletableDeferred<Unit>()
        sessions.opened.single().closing = closing
        log.clear()

        listening.send(Event.TurnOff)
        runCurrent()
        listening.send(Event.Toggle)
        runCurrent()
        // At once, so that the short exemption of a tap on the tile cannot run out while a transcription ends the close.
        assertEquals(listOf("close", "start"), log)

        // The running service, which the shutdown has not stopped yet, takes the new start and reports.
        listening.onForeground(service)
        runCurrent()
        assertEquals(1, sessions.opened.size)

        closing.complete(Unit)
        runCurrent()
        // The close does not stop the service that the new start asked for, and the new session opens after the close.
        assertEquals(listOf("close", "start", "closed", "open", "recognition ALL", "microphone on"), log)
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
    fun aMicrophoneThatFailsStartsAgainAfterASecond() = runTest {
        val listening = listeningWithSession()
        log.clear()

        sessions.opened.single().listener.microphoneFailed(readError())
        runCurrent()
        assertEquals(listOf("microphone off"), log)

        // A change of the state during the wait does not start the microphone early.
        listening.send(Event.Pause)
        runCurrent()
        advanceTimeBy(999)
        assertEquals(listOf("microphone off", "recognition NAMED"), log)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("microphone off", "recognition NAMED", "microphone on"), log)
        assertEquals(Mode.PAUSED, listening.state.value.mode)
        assertEquals("The microphone", reports.single().first)
    }

    @Test
    fun aMicrophoneThatFailsAgainWithinAMinuteOfItsRestartTurnsKestrelOff() = runTest {
        val listening = listeningWithSession()
        val events = sessions.opened.single().listener
        events.microphoneFailed(readError())
        advanceTimeBy(1_000)
        runCurrent()
        log.clear()

        // 59 s after the restart.
        advanceTimeBy(59_000)
        events.microphoneFailed(readError())
        runCurrent()

        assertEquals(listOf("microphone off", "close", "closed", "stop"), log)
        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(ListeningTexts.MICROPHONE_FAILED, shown.last())
        assertEquals(listOf("The microphone", "The microphone"), reports.map { it.first })
    }

    @Test
    fun aMicrophoneThatFailsAMinuteAfterItsRestartStartsAgain() = runTest {
        val listening = listeningWithSession()
        val events = sessions.opened.single().listener
        events.microphoneFailed(readError())
        advanceTimeBy(1_000)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        log.clear()

        events.microphoneFailed(readError())
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(listOf("microphone off", "microphone on"), log)
        assertEquals(Mode.LISTENING, listening.state.value.mode)
    }

    @Test
    fun aRestartThatFailsTurnsKestrelOff() = runTest {
        val listening = listeningWithSession()
        val session = sessions.opened.single()
        session.listener.microphoneFailed(readError())
        runCurrent()
        session.microphoneFailure = readError()
        log.clear()

        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(listOf("microphone on", "microphone off", "close", "closed", "stop"), log)
        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(ListeningTexts.MICROPHONE_FAILED, shown.last())
        assertEquals(listOf("The microphone", "Starting the microphone"), reports.map { it.first })
    }

    @Test
    fun aTurnOffDuringTheWaitCancelsTheRestart() = runTest {
        val listening = listeningWithSession()
        sessions.opened.single().listener.microphoneFailed(readError())
        runCurrent()
        listening.send(Event.TurnOff)
        runCurrent()
        listening.send(Event.Toggle)
        runCurrent()
        log.clear()

        listening.onForeground(Any())
        runCurrent()

        // The new session starts its microphone at once, not when the old session's wait would have ended.
        assertEquals(listOf("open", "recognition ALL", "microphone on"), log)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf("open", "recognition ALL", "microphone on"), log)

        // The old wait did not start the minute after a restart for the new session, so this first failure restarts it.
        sessions.opened.last().listener.microphoneFailed(readError())
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(Mode.LISTENING, listening.state.value.mode)
    }

    @Test
    fun aRestartInAnEarlierSessionDoesNotCountForTheNextOne() = runTest {
        val listening = listeningWithSession()
        sessions.opened.single().listener.microphoneFailed(readError())
        advanceTimeBy(1_000)
        runCurrent()
        listening.send(Event.TurnOff)
        runCurrent()
        listening.send(Event.Toggle)
        runCurrent()
        listening.onForeground(Any())
        runCurrent()
        log.clear()

        // Within a minute of the first session's restart, the new session's microphone fails for the first time.
        sessions.opened.last().listener.microphoneFailed(readError())
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(listOf("microphone off", "microphone on"), log)
        assertEquals(Mode.LISTENING, listening.state.value.mode)
    }

    @Test
    fun aMicrophoneThatFailsAsTheScreenGoesOffStartsAgainWithTheScreen() = runTest {
        val listening = listeningWithSession()
        val events = sessions.opened.single().listener
        events.microphoneFailed(readError())
        runCurrent()
        listening.send(Event.Screen(on = false))
        advanceTimeBy(1_000)
        runCurrent()
        log.clear()

        listening.send(Event.Screen(on = true))
        runCurrent()
        assertEquals(listOf("microphone on"), log)

        // The controller did not restart the microphone itself, so this failure is not one within a minute of a restart.
        events.microphoneFailed(readError())
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(Mode.LISTENING, listening.state.value.mode)
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
        assertEquals(listOf(ListeningTexts.LISTENING, ListeningTexts.MICROPHONE_FAILED), shown)
        assertEquals("Starting the microphone", reports.single().first)
    }

    @Test
    fun anErrorWhileFollowingTheStateTurnsKestrelOffAndTheStateIsStillFollowed() = runTest {
        val listening = listeningWithSession()
        listening.send(Event.Screen(on = false))
        runCurrent()
        sessions.opened.single().microphoneFailure = OutOfMemoryError("pthread_create failed")
        log.clear()

        listening.send(Event.Screen(on = true))
        runCurrent()
        assertEquals(Mode.OFF, listening.state.value.mode)
        assertTrue(reports.single().second is OutOfMemoryError)
        assertEquals(listOf("microphone on", "close", "closed", "stop"), log)

        // The next change of the state still starts the service.
        listening.send(Event.Toggle)
        runCurrent()
        assertEquals("start", log.last())
    }

    @Test
    fun anErrorWhileTheSessionClosesStillStopsTheService() = runTest {
        val errors = mutableListOf<Throwable>()
        val listening = listeningWithSession(catching(errors))
        sessions.opened.single().closeFailure = StackOverflowError()
        log.clear()

        listening.send(Event.TurnOff)
        runCurrent()

        assertEquals(listOf("close", "stop"), log)
        assertFalse(listening.sessionOpen.value)
        assertTrue(errors.single() is StackOverflowError)
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
    fun anEventThatAClosingSessionSendsAsKestrelTurnsOffNeverActsOnTheNextState() = runTest {
        val listening = listeningWithSession()
        val old = sessions.opened.single().listener

        // The owner turns Kestrel off and on again, and the old session's transcript arrives before the controller has
        // handled either: it must not run as a task in the new state.
        listening.send(Event.TurnOff)
        listening.send(Event.Toggle)
        old.send(Event.Heard("Kestrel, open the settings.", named = true))
        runCurrent()

        assertEquals(Mode.LISTENING, listening.state.value.mode)
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

    @Test
    fun offAndOnInOneRunOfTheOrchestratorStartTheServiceAgain() = runTest {
        val listening = listeningWithSession()
        log.clear()

        // Both events wait in the channel, so the Orchestrator handles them in one run, and its state ends as it began.
        listening.send(Event.TurnOff)
        listening.send(Event.Toggle)
        runCurrent()

        assertEquals(listOf("close", "closed", "stop", "start"), log)
        listening.onForeground(Any())
        runCurrent()
        assertTrue(listening.sessionOpen.value)
    }

    @Test
    fun offAndOnTwiceWhileASessionClosesLeaveTheNewServiceRunning() = runTest {
        val listening = listeningWithSession()
        val closing = CompletableDeferred<Unit>()
        sessions.opened.single().closing = closing
        // Off, on, off and on again while the first session still closes.
        for (event in listOf(Event.TurnOff, Event.Toggle, Event.TurnOff, Event.Toggle)) {
            listening.send(event)
            runCurrent()
        }
        log.clear()

        closing.complete(Unit)
        runCurrent()

        // Both starts went to Android before the close ended, so neither stop ends the service that they asked for.
        assertEquals(listOf("closed"), log)
        listening.onForeground(service)
        runCurrent()
        assertEquals(Mode.LISTENING, listening.state.value.mode)
        assertTrue(listening.sessionOpen.value)
    }

    @Test
    fun offAgainWhileASessionClosesStopsTheNewServiceOnceItReports() = runTest {
        val listening = listeningWithSession()
        val closing = CompletableDeferred<Unit>()
        sessions.opened.single().closing = closing
        log.clear()
        // Off, on and off again while the first session still closes: the start goes to Android at once.
        for (event in listOf(Event.TurnOff, Event.Toggle, Event.TurnOff)) {
            listening.send(event)
            runCurrent()
        }

        closing.complete(Unit)
        runCurrent()
        // The service has not called startForeground() yet, so neither shutdown may stop it.
        assertEquals(listOf("close", "start", "closed"), log)

        listening.onForeground(service)
        runCurrent()
        assertEquals(listOf("close", "start", "closed", "stop"), log)
        assertEquals(Mode.OFF, listening.state.value.mode)
        assertFalse(listening.sessionOpen.value)
    }

    @Test
    fun aStartThatAndroidRefusesWhileASessionClosesStopsTheServiceAfterTheClose() = runTest {
        val listening = listeningWithSession()
        val closing = CompletableDeferred<Unit>()
        sessions.opened.single().closing = closing
        log.clear()
        listening.send(Event.TurnOff)
        runCurrent()

        services.failure = SecurityException("Not allowed from the background")
        listening.send(Event.Toggle)
        runCurrent()
        closing.complete(Unit)
        runCurrent()

        // The shutdown of the refused start and the first shutdown each stop the service once the session has closed.
        assertEquals(listOf("close", "start", "closed", "stop", "stop"), log)
        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(ListeningTexts.CANNOT_START, shown.last())
        assertEquals(listOf("Starting the listening service"), reports.map { it.first })
    }

    @Test
    fun aRunningServiceThatCannotReenterTheForegroundTurnsKestrelOffAndSaysWhy() = runTest {
        val listening = listeningWithSession()
        log.clear()

        // A notification action's start failed; the service stops itself, and Android destroys it.
        listening.onForegroundFailed(SecurityException("No microphone permission"))
        runCurrent()
        listening.onServiceDestroyed(service)
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals("Starting the listening service", reports.single().first)
        assertEquals(ListeningTexts.CANNOT_START, shown.last())
        assertEquals(listOf("close", "closed", "stop"), log)
    }

    @Test
    fun aReportOfTheClosingServiceBeforeANewStartDoesNotStopTheNewService() = runTest {
        val listening = listeningWithSession()
        val closing = CompletableDeferred<Unit>()
        sessions.opened.single().closing = closing
        listening.send(Event.TurnOff)
        runCurrent()

        // A notification action reaches the old service while it closes, and then the owner turns Kestrel on again.
        listening.onForeground(service)
        runCurrent()
        listening.send(Event.Toggle)
        runCurrent()
        log.clear()
        closing.complete(Unit)
        runCurrent()
        listening.onForeground(service)
        runCurrent()

        assertEquals(listOf("closed", "open", "recognition ALL", "microphone on"), log)
        assertEquals(Mode.LISTENING, listening.state.value.mode)
        assertTrue(listening.sessionOpen.value)
    }

    @Test
    fun offAndOnWhileASessionOpensKeepTheSessionForTheNewStart() = runTest {
        val opening = CompletableDeferred<Unit>()
        sessions.opening = opening
        val listening = listening()
        listening.send(Event.Toggle)
        runCurrent()
        listening.onForeground(service)
        runCurrent()

        // Off and on again while the models load. The running service takes the new start and reports before the
        // opening ends.
        listening.send(Event.TurnOff)
        runCurrent()
        listening.send(Event.Toggle)
        runCurrent()
        listening.onForeground(service)
        runCurrent()
        opening.complete(Unit)
        runCurrent()

        assertEquals(1, sessions.opened.size)
        assertFalse("close" in log)
        assertEquals(Mode.LISTENING, listening.state.value.mode)
        assertTrue(listening.sessionOpen.value)
    }

    @Test
    fun anErrorWhileTheSessionOpensTurnsKestrelOff() = runTest {
        val errors = mutableListOf<Throwable>()
        sessions.failure = UnsatisfiedLinkError("libsherpa-onnx-jni.so")
        val listening = listening(catching(errors))
        listening.send(Event.Toggle)
        runCurrent()
        listening.onForeground(service)
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(listOf("start", "open", "stop"), log)
        assertTrue(errors.single() is UnsatisfiedLinkError)
    }

    @Test
    fun aClosedSessionsFailuresAreIgnored() = runTest {
        val listening = listeningWithSession()
        val old = sessions.opened.single().listener
        listening.send(Event.TurnOff)
        runCurrent()
        listening.send(Event.Toggle)
        runCurrent()
        listening.onForeground(Any())
        runCurrent()
        log.clear()
        reports.clear()

        old.microphoneFailed(IllegalStateException("The microphone failed with error -3"))
        old.transcriptionFailed(IllegalStateException("The recogniser failed"))
        runCurrent()

        assertEquals(Mode.LISTENING, listening.state.value.mode)
        assertTrue(log.isEmpty())
        assertTrue(reports.isEmpty())
    }

    @Test
    fun aFailureAfterKestrelIsOffShowsItsReasonOnItsOwn() = runTest {
        sessions.failure = ModelsNotReadyException(Engine.ASR, emptyList())
        val opening = CompletableDeferred<Unit>()
        sessions.opening = opening
        val listening = listening()
        listening.send(Event.Toggle)
        runCurrent()
        listening.onForeground(service)
        runCurrent()

        // Turned off while the models load; the missing model shows up only afterwards.
        listening.send(Event.TurnOff)
        runCurrent()
        opening.complete(Unit)
        runCurrent()

        assertEquals(listOf(ListeningTexts.LISTENING, ListeningTexts.OFF, ListeningTexts.MODELS_MISSING), shown)
    }

    @Test
    fun eachOfTwoOverlappingRepliesEndsOnce() = runTest {
        val listening = listeningWithSession()
        val session = sessions.opened.single()
        listening.send(Event.Heard("Kestrel", named = true))
        runCurrent()
        // A transcript that arrives while "Yes?" still plays is the task, and the stub's reply queues behind "Yes?".
        listening.send(Event.Heard("Open the settings.", named = false))
        runCurrent()
        assertEquals(2, listening.state.value.speeches)

        session.replies[0].complete(Spoke.FINISHED)
        runCurrent()
        assertTrue(listening.state.value.speaking)
        session.replies[1].complete(Spoke.REFUSED)
        runCurrent()
        assertFalse(listening.state.value.speaking)
    }

    @Test
    fun aTranscriptionThatFailsWhileTheSessionIsOpenIsReported() = runTest {
        val listening = listeningWithSession()

        sessions.opened.single().listener.transcriptionFailed(IllegalStateException("The recogniser failed"))
        runCurrent()

        assertEquals("A transcription", reports.single().first)
        assertEquals(Mode.LISTENING, listening.state.value.mode)
    }

    @Test
    fun aCancelledReplyEndsWithoutAFailure() = runTest {
        val listening = listeningWithSession()
        listening.send(Event.Heard("Kestrel", named = true))
        runCurrent()
        shown.clear()

        // As player.close() cancels a reply in progress.
        sessions.opened.single().replies.single().cancel()
        runCurrent()

        assertFalse(listening.state.value.speaking)
        assertTrue(shown.isEmpty())
        assertTrue(reports.isEmpty())
    }

    @Test
    fun anErrorFromAnEffectTurnsKestrelOffAndTheOrchestratorRunsOn() = runTest {
        val errors = mutableListOf<Throwable>()
        val listening = listeningWithSession(catching(errors))
        sessions.opened.single().speakFailure = OutOfMemoryError("Failed to allocate")
        log.clear()

        listening.send(Event.Heard("Kestrel", named = true))
        runCurrent()
        assertEquals(Mode.OFF, listening.state.value.mode)
        assertFalse(listening.state.value.speaking)
        assertTrue(reports.single().second is OutOfMemoryError)
        assertEquals(listOf("close", "closed", "stop"), log.takeLast(3))

        // The Orchestrator still handles events.
        listening.send(Event.Toggle)
        runCurrent()
        assertEquals("start", log.last())
        assertTrue(errors.isEmpty())
    }

    /** What MicCapture reports when AudioRecord.read returns ERROR_DEAD_OBJECT, after Android's audio server restarted. */
    private fun readError() = IllegalStateException("The microphone failed with error -6")

    /** A scope whose failures go to [errors] instead of failing the test, for the errors that the controller rethrows. */
    private fun TestScope.catching(errors: MutableList<Throwable>): CoroutineScope = CoroutineScope(
        backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]) +
            CoroutineExceptionHandler { _, e -> errors += e },
    )

    private fun TestScope.listening(scope: CoroutineScope = backgroundScope): Listening = Listening(
        scope = scope,
        thread = StandardTestDispatcher(testScheduler),
        sessions = sessions,
        services = services,
        bar = { shown += it },
        report = { what, error -> reports += what to error },
        openSettings = {
            log += "open settings"
            settingsFailure?.let { throw it }
        },
        memory = memory,
        microphones = { microphone },
    )

    /** Turns listening on, and lets the service report the foreground, so that a session opens. */
    private fun TestScope.listeningWithSession(scope: CoroutineScope = backgroundScope): Listening {
        val listening = listening(scope)
        listening.send(Event.Toggle)
        runCurrent()
        listening.onForeground(service)
        runCurrent()
        return listening
    }

    private inner class FakeSessions : VoiceSessions {
        val opened = mutableListOf<FakeSession>()
        val microphones = mutableListOf<Microphone>()
        var failure: Throwable? = null
        var opening: CompletableDeferred<Unit>? = null
        var microphoneFailure: Throwable? = null

        override suspend fun open(microphone: Microphone, listener: SessionListener): Session {
            log += "open"
            microphones += microphone
            opening?.await()
            failure?.let { throw it }
            return FakeSession(listener, microphoneFailure).also { opened += it }
        }
    }

    private inner class FakeSession(val listener: SessionListener, var microphoneFailure: Throwable?) : Session {
        val said = mutableListOf<String>()
        val replies = mutableListOf<CompletableDeferred<Spoke>>()
        var closing: CompletableDeferred<Unit>? = null
        var closeFailure: Throwable? = null
        var speakFailure: Throwable? = null
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
            speakFailure?.let { throw it }
            return CompletableDeferred<Spoke>().also { replies += it }
        }

        override fun stopSpeaking() {
            log += "stop speaking"
        }

        override suspend fun close() {
            log += "close"
            closing?.await()
            closeFailure?.let { throw it }
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

    private class FakeMemory : ModeMemory {
        var stored = Mode.OFF
        var reads = 0
        var readFailure: Exception? = null
        var reading: CompletableDeferred<Unit>? = null
        var writeFailure: Exception? = null
        var writing: CompletableDeferred<Unit>? = null
        val writes = mutableListOf<Mode>()

        override suspend fun read(): Mode {
            reads++
            reading?.await()
            readFailure?.let { throw it }
            return stored
        }

        override suspend fun write(mode: Mode) {
            writing?.await()
            writeFailure?.let { throw it }
            writes += mode
            stored = mode
        }
    }

    private class FakeMicrophone : Microphone {
        override val silenced: StateFlow<Boolean> = MutableStateFlow(false)
        override val echoCancelling = false

        override fun start(onFrame: (FloatArray) -> Unit, onError: (Exception) -> Unit) = Unit

        override fun stop() = Unit
    }

    @Test
    fun anErrorWhileStoringTheModeIsReportedAndLaterModesAreStillStored() = runTest {
        val errors = mutableListOf<Throwable>()
        val writes = mutableListOf<Mode>()
        var failure: Throwable? = OutOfMemoryError("Failed to allocate")
        val listening = Listening(
            scope = catching(errors),
            thread = StandardTestDispatcher(testScheduler),
            sessions = sessions,
            services = services,
            bar = { shown += it },
            report = { what, error -> reports += what to error },
            openSettings = {},
            // Its first write throws an Error, and the later ones work.
            memory = object : ModeMemory {
                override suspend fun read(): Mode = Mode.OFF

                override suspend fun write(mode: Mode) {
                    failure?.let {
                        failure = null
                        throw it
                    }
                    writes += mode
                }
            },
            microphones = { microphone },
        )
        listening.send(Event.Toggle)
        runCurrent()
        listening.onForeground(service)
        runCurrent()
        assertEquals("Storing the listening mode", reports.single().first)
        assertTrue(reports.single().second is OutOfMemoryError)

        // The Off that the owner chooses next is still stored.
        listening.send(Event.TurnOff)
        runCurrent()
        assertEquals(listOf(Mode.OFF), writes)
        assertTrue(errors.isEmpty())
    }
}
