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
    private var settingsCanOpen = true
    private val conditions = FakeConditions()
    private val notices = FakeNotices()
    private val memory = FakeMemory()
    private val exits = FakeExits()

    // The wall clock, in milliseconds.
    private var now = 1_000_000L

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
        // Kestrel listens again, so the notification that said why it stopped goes.
        assertEquals(1, notices.cleared)
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
        // The bar shows "Yes?" at the start of the wait for the task (section 8.9), and not again when it was spoken.
        assertEquals(listOf(ListeningTexts.LISTENING, "Kestrel", ListeningTexts.YES), shown)
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
        listening.send(Event.Heard("Kestrel, open the settings.", named = true))
        runCurrent()
        session.replies[1].completeExceptionally(IllegalStateException("The voice failed"))
        runCurrent()
        listening.send(Event.Heard("Kestrel", named = true))
        runCurrent()
        session.replies[2].completeExceptionally(IllegalStateException("The voice failed"))
        runCurrent()

        // A refused and a failed reply go to the bar (section 14.6). "Yes?" shows at the start of the wait for the
        // task, and a failed "Yes?" is not shown a second time.
        assertEquals(
            listOf(ListeningTexts.NO_TASKS, "Kestrel, open the settings.", ListeningTexts.NO_TASKS, "Kestrel", ListeningTexts.YES),
            shown,
        )
        assertEquals(listOf("A reply", "A reply"), reports.map { it.first })
        assertFalse(listening.state.value.speaking)
    }

    @Test
    fun aReplyThatAndroidInterruptsIsShownOnTheBar() = runTest {
        val listening = listeningWithSession()
        val session = sessions.opened.single()
        listening.send(Event.Heard("Kestrel, open the settings.", named = true))
        runCurrent()
        shown.clear()

        // Android took the audio focus away, for a call or an app that asks for exclusive focus (section 14.6).
        session.replies.single().complete(Spoke.INTERRUPTED)
        runCurrent()

        assertEquals(listOf(ListeningTexts.NO_TASKS), shown)
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
        // The bar shows "Yes?" at the start of the wait for the task; a "Yes?" that cannot be spoken is not shown
        // again.
        assertEquals(listOf(ListeningTexts.LISTENING, "Kestrel", ListeningTexts.YES), shown)

        // The answer is a task, and its reply goes to the bar, because there is no voice (section 14.6).
        listening.send(Event.Heard("Open the settings.", named = false))
        runCurrent()
        assertEquals(ListeningTexts.NO_TASKS, shown.last())
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
        // Kestrel never posts the problems notification for an Off that the owner chose.
        assertTrue(notices.shown.isEmpty())
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
        // The reason takes the place of "Off", which would replace it at once. Android refused a start in the
        // background.
        assertEquals(listOf(ListeningTexts.LISTENING, ListeningTexts.BACKGROUND_START), shown)
        assertEquals("Starting the listening service", reports.single().first)
        // The bar exists only while the accessibility service runs, so the reason is a notification too.
        assertEquals(listOf(StopReason.BACKGROUND_START), notices.shown)
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
    fun kestrelOpenSettingsWithoutTheAccessibilityServiceSaysWhy() = runTest {
        // Android blocks the start from the background without an exception, and the bar does not exist.
        settingsCanOpen = false
        val listening = listeningWithSession()
        log.clear()

        listening.send(Event.Heard("Kestrel, open settings.", named = true))
        runCurrent()

        // Recognition is off while Kestrel says why.
        assertEquals(listOf("open settings", "recognition NONE"), log)
        assertEquals(listOf(ListeningTexts.SETTINGS_NEED_SERVICE), sessions.opened.single().said)
        assertTrue(reports.isEmpty())
    }

    @Test
    fun aServiceThatCannotEnterTheForegroundWithoutTheMicrophonePermissionAsksForIt() = runTest {
        // Android 14 and later refuse a microphone service without the permission at startForeground().
        conditions.microphone = false
        val listening = listening()
        listening.send(Event.Toggle)
        runCurrent()

        listening.onForegroundFailed(SecurityException("Starting FGS with type microphone requires permissions"))
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(listOf(ListeningTexts.LISTENING, ListeningTexts.MICROPHONE_PERMISSION), shown)
    }

    @Test
    fun aServiceThatAndroidRefusesWithoutTheMicrophonePermissionButWithoutASecurityExceptionIsABackgroundStart() = runTest {
        // Only a SecurityException from startForeground() means the missing permission; startForegroundService() throws
        // another exception when Android refuses a start in the background.
        conditions.microphone = false
        services.failure = IllegalStateException("Not allowed in the background")
        val listening = listening()

        listening.send(Event.Toggle)
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(listOf(ListeningTexts.LISTENING, ListeningTexts.BACKGROUND_START), shown)
        assertEquals(listOf(StopReason.BACKGROUND_START), notices.shown)
    }

    @Test
    fun aMicrophoneThatDoesNotStartWithoutThePermissionAsksForIt() = runTest {
        // Android 13, as on the A71, starts the service, and the microphone fails instead.
        conditions.microphone = false
        sessions.microphoneFailure = SecurityException("Kestrel may not record audio")
        val listening = listening()
        listening.send(Event.Toggle)
        runCurrent()

        listening.onForeground(service)
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(listOf(ListeningTexts.LISTENING, ListeningTexts.MICROPHONE_PERMISSION), shown)
        // The session opened, but Kestrel never listened, so the notification that says why stays (section 14.6).
        assertEquals(StopReason.MICROPHONE_PERMISSION, notices.visible)
    }

    @Test
    fun speechEnginesThatCannotLoadSaySo() = runTest {
        sessions.failure = IllegalStateException("The recogniser could not load")
        val listening = listening()
        listening.send(Event.Toggle)
        runCurrent()

        listening.onForeground(service)
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(listOf(ListeningTexts.LISTENING, ListeningTexts.ENGINES_FAILED), shown)
        assertEquals("Opening the voice session", reports.single().first)
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
    fun kestrelStaysOffAfterTheOwnerStoppedIt() = runTest {
        // A force stop, "Stop app" or Stop in "Active apps" ended the process that stored Listening (prototype P2).
        memory.stored = Mode.LISTENING
        memory.writtenAt = 500_000
        exits.record = ExitRecord(ExitReason.OWNER_STOPPED, timestamp = 600_000)
        val listening = listening()

        listening.onAccessibilityServiceConnected()
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertTrue(log.isEmpty())
        assertEquals(listOf(Mode.OFF), memory.writes)
        assertTrue(notices.shown.isEmpty())
    }

    @Test
    fun aCrashSoonAfterARestoreKeepsKestrelOffAndSaysSo() = runTest {
        memory.stored = Mode.LISTENING
        memory.restoredAt = 500_000
        memory.writtenAt = 510_000
        // 100 s after the restore.
        exits.record = ExitRecord(ExitReason.CRASH, timestamp = 600_000)
        val listening = listening()

        listening.onAccessibilityServiceConnected()
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertTrue(log.isEmpty())
        assertEquals(listOf(Mode.OFF), memory.writes)
        assertEquals(listOf(ListeningTexts.CRASHED_AFTER_RESTORE), shown)
        assertEquals(listOf(StopReason.CRASHED_AFTER_RESTORE), notices.shown)
    }

    @Test
    fun aCrashBeforeTheRestoredSessionOpenedKeepsKestrelOffAndSaysSo() = runTest {
        // The last restore stored Off and turned Kestrel on, and a native crash ended the process while the models
        // loaded, so Listening was never stored again.
        memory.stored = Mode.OFF
        memory.writtenAt = 500_000
        memory.restoredAt = 500_000
        exits.record = ExitRecord(ExitReason.CRASH, timestamp = 530_000)
        val listening = listening()

        listening.onAccessibilityServiceConnected()
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertTrue(log.isEmpty())
        assertEquals(listOf(StopReason.CRASHED_AFTER_RESTORE), notices.shown)
        // Said once: the next process finds an Off without the time of a restore.
        assertEquals(null, memory.restoredAt)
    }

    @Test
    fun aRestoredProcessThatEndedWithoutACrashBeforeItsSessionOpenedStaysOffAndSaysSo() = runTest {
        // The last restore stored Off and turned Kestrel on, and Android ended the process while the models loaded, for
        // example with the low-memory killer. "Off first" keeps Kestrel off, and the owner did not choose that.
        memory.stored = Mode.OFF
        memory.writtenAt = 500_000
        memory.restoredAt = 500_000
        exits.record = ExitRecord(ExitReason.OTHER, timestamp = 530_000)
        val listening = listening()

        listening.onAccessibilityServiceConnected()
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertTrue(log.isEmpty())
        assertEquals(listOf(ListeningTexts.CRASHED_AFTER_RESTORE), shown)
        assertEquals(listOf(StopReason.CRASHED_AFTER_RESTORE), notices.shown)
        assertEquals(null, memory.restoredAt)
    }

    @Test
    fun aRestoredProcessThatTheOwnerStoppedBeforeItsSessionOpenedStaysOffSilently() = runTest {
        memory.stored = Mode.OFF
        memory.writtenAt = 500_000
        memory.restoredAt = 500_000
        exits.record = ExitRecord(ExitReason.OWNER_STOPPED, timestamp = 530_000)
        val listening = listening()

        listening.onAccessibilityServiceConnected()
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertTrue(log.isEmpty())
        assertTrue(shown.isEmpty())
        assertTrue(notices.shown.isEmpty())
        assertEquals(null, memory.restoredAt)
    }

    @Test
    fun aRestoredProcessThatEndedBeforeItsSessionOpenedSaysSoAlsoWithoutTheRecords() = runTest {
        memory.stored = Mode.OFF
        memory.writtenAt = 500_000
        memory.restoredAt = 500_000
        exits.failure = SecurityException("No access to the records")
        val listening = listening()

        listening.onAccessibilityServiceConnected()
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertTrue(log.isEmpty())
        assertEquals("Reading the exit records", reports.single().first)
        assertEquals(listOf(StopReason.CRASHED_AFTER_RESTORE), notices.shown)
    }

    @Test
    fun anOffWithoutTheTimeOfARestoreDoesNotReadTheRecords() = runTest {
        // The owner's Off, or a restore's decision to stay off.
        memory.stored = Mode.OFF
        memory.writtenAt = 500_000
        exits.record = ExitRecord(ExitReason.CRASH, timestamp = 530_000)
        val listening = listening()

        listening.onAccessibilityServiceConnected()
        runCurrent()

        assertEquals(0, exits.reads)
        assertEquals(Mode.OFF, listening.state.value.mode)
        assertTrue(notices.shown.isEmpty())
    }

    @Test
    fun aCrashTwoMinutesAfterARestoreRestoresListening() = runTest {
        memory.stored = Mode.LISTENING
        memory.restoredAt = 500_000
        memory.writtenAt = 510_000
        exits.record = ExitRecord(ExitReason.CRASH, timestamp = 620_000)
        val listening = listening()

        listening.onAccessibilityServiceConnected()
        runCurrent()

        assertEquals(Mode.LISTENING, listening.state.value.mode)
        assertEquals(listOf("start"), log)
    }

    @Test
    fun aCrashAfterTheOwnersOwnStartRestoresListening() = runTest {
        // The owner turned Kestrel off and on after the last restore, which cleared its time; listen-check.ps1's
        // restart check does this before its 'am crash'.
        memory.stored = Mode.LISTENING
        memory.writtenAt = 510_000
        exits.record = ExitRecord(ExitReason.CRASH, timestamp = 520_000)
        val listening = listening()

        listening.onAccessibilityServiceConnected()
        runCurrent()

        assertEquals(Mode.LISTENING, listening.state.value.mode)
        assertEquals(listOf("start"), log)
        assertTrue(notices.shown.isEmpty())
    }

    @Test
    fun otherEndsOfTheProcessRestoreListening() = runTest {
        // Such as a low-memory kill or a package update.
        memory.stored = Mode.LISTENING
        memory.writtenAt = 500_000
        exits.record = ExitRecord(ExitReason.OTHER, timestamp = 600_000)
        val listening = listening()

        listening.onAccessibilityServiceConnected()
        runCurrent()

        assertEquals(Mode.LISTENING, listening.state.value.mode)
        assertEquals(listOf("start"), log)
    }

    @Test
    fun aRecordFromBeforeTheStoredModeIsIgnoredAsAfterAReboot() = runTest {
        // A reboot leaves no record of its own, so the newest one belongs to an older process.
        memory.stored = Mode.PAUSED
        memory.writtenAt = 500_000
        exits.record = ExitRecord(ExitReason.OWNER_STOPPED, timestamp = 400_000)
        val listening = listening()

        listening.onAccessibilityServiceConnected()
        runCurrent()

        assertEquals(Mode.PAUSED, listening.state.value.mode)
        assertEquals(listOf("start"), log)
    }

    @Test
    fun aModeWithoutATimeLetsTheNewestRecordDecide() = runTest {
        // Stored by plan 2C-2's version, which kept no times.
        memory.stored = Mode.LISTENING
        exits.record = ExitRecord(ExitReason.OWNER_STOPPED, timestamp = 400_000)
        val listening = listening()

        listening.onAccessibilityServiceConnected()
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertTrue(log.isEmpty())
    }

    @Test
    fun exitRecordsThatCannotBeReadDoNotStopTheRestore() = runTest {
        memory.stored = Mode.LISTENING
        exits.failure = SecurityException("No access to the records")
        val listening = listening()

        listening.onAccessibilityServiceConnected()
        runCurrent()

        assertEquals(Mode.LISTENING, listening.state.value.mode)
        assertEquals("Reading the exit records", reports.single().first)
    }

    @Test
    fun theOwnersChoiceWhileTheExitRecordsAreReadIsKept() = runTest {
        memory.stored = Mode.LISTENING
        exits.reading = CompletableDeferred()
        val listening = listening()
        listening.onAccessibilityServiceConnected()
        runCurrent()

        // While the record is read, the owner turns Kestrel on and off again.
        listening.send(Event.Toggle)
        runCurrent()
        listening.send(Event.TurnOff)
        runCurrent()
        exits.reading?.complete(Unit)
        runCurrent()

        // Only the owner's Off was stored; the restore wrote nothing and started nothing more.
        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(listOf(Mode.OFF), memory.writes)
        assertEquals(listOf("start"), log)
    }

    @Test
    fun aRestoreKeepsItsTimeUntilKestrelTurnsOff() = runTest {
        memory.stored = Mode.LISTENING
        now = 700_000
        val listening = listening()
        listening.onAccessibilityServiceConnected()
        runCurrent()
        assertEquals(700_000L, memory.restoredAt)

        now = 705_000
        listening.onForeground(service)
        runCurrent()
        // Listening, stored once the session is open, keeps the time of the restore for the rule about crashes.
        assertEquals(listOf(Mode.OFF, Mode.LISTENING), memory.writes)
        assertEquals(705_000L, memory.writtenAt)
        assertEquals(700_000L, memory.restoredAt)

        // A start after an Off is the owner's, so a crash after it is not one soon after a restore.
        listening.send(Event.TurnOff)
        runCurrent()
        assertEquals(Mode.OFF, memory.stored)
        assertEquals(null, memory.restoredAt)
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
        // With the microphone allowed, the start came too late after a tap (prototype P2), as a start in the
        // background.
        assertEquals(listOf(ListeningTexts.LISTENING, ListeningTexts.BACKGROUND_START), shown)
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
        assertEquals(listOf(StopReason.MODELS_MISSING), notices.shown)
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
        assertEquals(ListeningTexts.STOPPED_BY_ANDROID, shown.last())
        // The stop names no start: the destroyed instance is not kept, and a new instance counts its starts anew.
        assertEquals(listOf<Pair<Any?, Int>>(null to 0), services.stops)
    }

    @Test
    fun anEndOfTheAdoptedStartActionsServiceBeforeItsStartIsHandledStartsANewService() = runTest {
        val listening = listening()

        // Android destroys the service that "Start listening" started before the Orchestrator has handled its
        // Event.Start, so the mode is still Off.
        listening.onStartAction(service)
        listening.onServiceDestroyed(service)
        runCurrent()

        // The Event.Start asks Android for a new service, and no session opens without one.
        assertEquals(Mode.LISTENING, listening.state.value.mode)
        assertEquals(listOf("start"), log)
        assertFalse(listening.sessionOpen.value)

        listening.onForeground(Any())
        runCurrent()
        assertEquals(listOf("start", "open", "recognition ALL", "microphone on"), log)
        assertTrue(listening.sessionOpen.value)
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
    fun aSilencedMicrophoneInADemotedServiceUnderTheBatteryRestrictionTurnsKestrelOff() = runTest {
        // Started from the tile without the accessibility service: about 30 s after the panel closed, Android took the
        // foreground from the service without a word, and 5 s later it silenced the microphone (prototype P2).
        conditions.restricted = true
        conditions.demoted = true
        val listening = listeningWithSession()
        log.clear()

        listening.send(Event.MicBusy(true))
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(listOf("close", "closed", "stop"), log)
        // Not "Microphone blocked", which would stay until Android stops the service a minute later.
        assertEquals(listOf(ListeningTexts.LISTENING, ListeningTexts.BATTERY_RESTRICTED), shown)
        assertEquals(listOf(StopReason.BATTERY_RESTRICTED), notices.shown)
    }

    @Test
    fun aSilencedMicrophoneIsOnlyShownUnlessTheServiceWasDemotedUnderTheRestriction() = runTest {
        // Demoted without the restriction first, and then restricted while the service keeps the foreground.
        conditions.demoted = true
        val listening = listeningWithSession()
        listening.send(Event.MicBusy(true))
        runCurrent()
        conditions.demoted = false
        conditions.restricted = true
        listening.send(Event.MicBusy(false))
        listening.send(Event.MicBusy(true))
        runCurrent()

        assertEquals(Mode.LISTENING, listening.state.value.mode)
        assertEquals(listOf(ListeningTexts.LISTENING, ListeningTexts.MIC_BUSY, ListeningTexts.MIC_BUSY), shown)
        assertTrue(notices.shown.isEmpty())
    }

    @Test
    fun aStopByAndroidUnderTheBatteryRestrictionSaysSo() = runTest {
        conditions.restricted = true
        val listening = listeningWithSession()

        listening.onServiceDestroyed(service)
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(ListeningTexts.BATTERY_RESTRICTED, shown.last())
        assertEquals(listOf(StopReason.BATTERY_RESTRICTED), notices.shown)
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
    fun theStartActionFromOffTurnsKestrelOnInTheServiceThatItStarted() = runTest {
        val listening = listening()

        // The problems notification's "Start listening" started the service, which runs in the foreground.
        listening.onStartAction(service)
        runCurrent()

        assertEquals(Mode.LISTENING, listening.state.value.mode)
        // Kestrel asks Android for no second start, and it does not stop the service as one that nobody asked for.
        assertEquals(listOf("open", "recognition ALL", "microphone on"), log)
        assertTrue(listening.sessionOpen.value)
        assertEquals(1, notices.cleared)
    }

    @Test
    fun theStartActionWhileKestrelIsOnChangesNothing() = runTest {
        val listening = listeningWithSession()
        listening.send(Event.Pause)
        runCurrent()
        log.clear()

        // A tap on a notification that an earlier failure left, after the owner started Kestrel another way.
        listening.onStartAction(service)
        runCurrent()

        assertEquals(Mode.PAUSED, listening.state.value.mode)
        assertTrue(log.isEmpty())
        assertEquals(1, sessions.opened.size)
    }

    @Test
    fun aTurnOffWhileTheStartActionsSessionOpensStopsTheService() = runTest {
        val opening = CompletableDeferred<Unit>()
        sessions.opening = opening
        val listening = listening()
        listening.onStartAction(service)
        runCurrent()

        listening.send(Event.TurnOff)
        runCurrent()
        opening.complete(Unit)
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(listOf("open", "close", "closed", "stop"), log)
        assertFalse(listening.sessionOpen.value)
    }

    @Test
    fun theStartActionWhileASessionClosesKeepsTheServiceForTheNewSession() = runTest {
        val listening = listeningWithSession()
        val closing = CompletableDeferred<Unit>()
        sessions.opened.single().closing = closing
        listening.send(Event.TurnOff)
        runCurrent()
        log.clear()

        // The owner taps "Start listening" while the old session still closes, and Android gives the start to the
        // service that runs.
        listening.onStartAction(service)
        runCurrent()
        closing.complete(Unit)
        runCurrent()

        // The close does not stop the service that the start action asked for, and the new session opens after it.
        assertEquals(listOf("closed", "open", "recognition ALL", "microphone on"), log)
        assertEquals(Mode.LISTENING, listening.state.value.mode)
    }

    @Test
    fun aStopDecidedJustBeforeTheStartActionWasAdoptedLeavesItsServiceRunning() = runTest {
        val listening = stopDecidedJustBeforeAStartAction()

        // The controller adopts start 2 and turns Kestrel on, and only then does the main thread run the stop.
        runCurrent()
        val destroyed = services.runPostedStops(newest = 2)
        destroyed.forEach { listening.onServiceDestroyed(it) }
        runCurrent()

        assertTrue(destroyed.isEmpty())
        assertListeningInTheAdoptedService(listening)
    }

    @Test
    fun aStopThatRunsBeforeTheAdoptedStartTurnsKestrelOnLeavesItsServiceRunning() = runTest {
        val listening = stopDecidedJustBeforeAStartAction()

        // The main thread runs the stop before the Orchestrator has handled the Event.Start of start 2. Had the stop
        // ended the service, destroyed() would come before that Event.Start, and Kestrel would listen without a service.
        val destroyed = services.runPostedStops(newest = 2)
        destroyed.forEach { listening.onServiceDestroyed(it) }
        runCurrent()

        assertTrue(destroyed.isEmpty())
        assertListeningInTheAdoptedService(listening)
    }

    /**
     * Turns Kestrel off and lets the close end, so that the shutdown decides to stop the service, just before the
     * controller's thread handles the owner's tap on "Start listening", whose start onStartCommand has already received
     * as the service's start 2. The stop waits for the main thread.
     */
    private fun TestScope.stopDecidedJustBeforeAStartAction(): Listening {
        val listening = listeningWithSession()
        val closing = CompletableDeferred<Unit>()
        sessions.opened.single().closing = closing
        listening.send(Event.TurnOff)
        runCurrent()
        log.clear()
        closing.complete(Unit)
        runCurrent()
        listening.onStartAction(service)
        // The stop names start 1, the newest that Kestrel had seen, so stopSelfResult() keeps the service for start 2.
        // A stop of the newest start would end it, as the stop of every start did before.
        assertEquals(listOf(service to 1), services.stops)
        return listening
    }

    private fun TestScope.assertListeningInTheAdoptedService(listening: Listening) {
        assertEquals(listOf("closed", "stop", "open", "recognition ALL", "microphone on"), log)
        assertEquals(Mode.LISTENING, listening.state.value.mode)
        assertTrue(listening.sessionOpen.value)
        // Kestrel knows the service as its own, so it turns off and says why when Android ends the service later.
        listening.onServiceDestroyed(service)
        runCurrent()
        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(ListeningTexts.STOPPED_BY_ANDROID, shown.last())
    }

    @Test
    fun aProblemsNotificationThatCannotBePostedIsReportedAndKestrelStillTurnsOff() = runTest {
        val listening = listeningWithSession()
        notices.failure = SecurityException("Not allowed to post")

        listening.onServiceDestroyed(service)
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(ListeningTexts.STOPPED_BY_ANDROID, shown.last())
        assertEquals("Posting the problems notification", reports.single().first)
        assertTrue(reports.single().second is SecurityException)
    }

    @Test
    fun aStartActionThatCannotEnterTheForegroundSaysWhyWhileKestrelIsOff() = runTest {
        val listening = listening()

        // The owner tapped "Start listening", and the service stops itself after the failure.
        listening.onForegroundFailed(SecurityException("Foreground service of type microphone not allowed"), startAction = true)
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(listOf(ListeningTexts.BACKGROUND_START), shown)
        assertEquals(listOf(StopReason.BACKGROUND_START), notices.shown)
        assertEquals("Starting the listening service", reports.single().first)
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
        assertEquals(ListeningTexts.BACKGROUND_START, shown.last())
        assertEquals(listOf("Starting the listening service"), reports.map { it.first })
    }

    @Test
    fun aRunningServiceThatCannotReenterTheForegroundTurnsKestrelOffAndSaysWhy() = runTest {
        val listening = listeningWithSession()
        log.clear()

        // A newer start, such as "Start listening", failed; the service stops itself, and Android destroys it before
        // the Orchestrator has turned Kestrel off. The first reason stays.
        listening.onForegroundFailed(SecurityException("No microphone permission"))
        listening.onServiceDestroyed(service)
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals("Starting the listening service", reports.single().first)
        assertEquals(ListeningTexts.BACKGROUND_START, shown.last())
        assertEquals(listOf("close", "closed", "stop"), log)
    }

    @Test
    fun aReportOfTheClosingServiceBeforeANewStartDoesNotStopTheNewService() = runTest {
        val listening = listeningWithSession()
        val closing = CompletableDeferred<Unit>()
        sessions.opened.single().closing = closing
        listening.send(Event.TurnOff)
        runCurrent()

        // The old service, which still closes, receives a start that Kestrel no longer wants, and its report reaches
        // Listening; then the owner turns Kestrel on again.
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
        // Section 14.6: the native library of the engines did not load.
        assertEquals(ListeningTexts.ENGINES_FAILED, shown.last())
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
        // The owner chose Off, so the reason shows on the bar only.
        assertTrue(notices.shown.isEmpty())
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
            settingsCanOpen
        },
        memory = memory,
        conditions = conditions,
        notices = notices,
        exits = exits,
        clock = { now },
        microphones = { microphone },
    )

    // Android gives every start of the service the next start ID, and onStartCommand reports it with the instance.
    private var startIds = 0

    /** The service reports the foreground after its next start. */
    private fun Listening.onForeground(instance: Any) = onForeground(instance, ++startIds)

    /** The service reports the problems notification's "Start listening" as its next start. */
    private fun Listening.onStartAction(instance: Any) = onStartAction(instance, ++startIds)

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

        // What each stop named: the newest start that Kestrel had seen, by its instance and its start ID.
        val stops = mutableListOf<Pair<Any?, Int>>()

        // The stops that wait for the main thread.
        private val posted = mutableListOf<Pair<Any?, Int>>()

        override fun stop(instance: Any?, startId: Int) {
            log += "stop"
            stops += instance to startId
            posted += instance to startId
        }

        /**
         * Runs the stops that wait for the main thread, as ListeningService does with stopSelfResult(): a stop ends the
         * service only when it names the service's newest start, [newest]. Returns the instances that Android destroys.
         */
        fun runPostedStops(newest: Int): List<Any> {
            val ended = posted.filter { it.second == newest }.mapNotNull { it.first }
            posted.clear()
            return ended
        }
    }

    private class FakeMemory : ModeMemory {
        var stored = Mode.OFF
        var writtenAt: Long? = null
        var restoredAt: Long? = null
        var reads = 0
        var readFailure: Exception? = null
        var reading: CompletableDeferred<Unit>? = null
        var writeFailure: Exception? = null
        var writing: CompletableDeferred<Unit>? = null
        val writes = mutableListOf<Mode>()

        override suspend fun read(): StoredMode {
            reads++
            reading?.await()
            readFailure?.let { throw it }
            return StoredMode(stored, writtenAt, restoredAt)
        }

        override suspend fun write(mode: Mode, writtenAt: Long, restoredAt: Long?) {
            writing?.await()
            writeFailure?.let { throw it }
            writes += mode
            stored = mode
            this.writtenAt = writtenAt
            this.restoredAt = restoredAt
        }
    }

    private class FakeExits : ExitRecords {
        var record: ExitRecord? = null
        var failure: Exception? = null
        var reading: CompletableDeferred<Unit>? = null
        var reads = 0

        override suspend fun newest(): ExitRecord? {
            reads++
            reading?.await()
            failure?.let { throw it }
            return record
        }
    }

    private class FakeNotices : StopNotices {
        val shown = mutableListOf<StopReason>()
        var cleared = 0

        // The notification that the owner sees now.
        var visible: StopReason? = null
        var failure: Exception? = null

        override fun show(reason: StopReason) {
            failure?.let { throw it }
            shown += reason
            visible = reason
        }

        override fun clear() {
            cleared++
            visible = null
        }
    }

    private class FakeConditions : ListeningConditions {
        var microphone = true
        var restricted = false
        var demoted = false

        override fun microphoneAllowed() = microphone

        override fun backgroundRestricted() = restricted

        override fun demoted(service: Any) = demoted
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
            openSettings = { true },
            conditions = conditions,
            notices = notices,
            exits = exits,
            clock = { now },
            // Its first write throws an Error, and the later ones work.
            memory = object : ModeMemory {
                override suspend fun read(): StoredMode = StoredMode(Mode.OFF)

                override suspend fun write(mode: Mode, writtenAt: Long, restoredAt: Long?) {
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
