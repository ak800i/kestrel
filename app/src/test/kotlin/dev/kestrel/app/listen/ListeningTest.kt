package dev.kestrel.app.listen

import dev.kestrel.app.commands.CommandConversation
import dev.kestrel.app.commands.CommandReport
import dev.kestrel.app.commands.CommandRunner
import dev.kestrel.app.conversation.InputIds
import dev.kestrel.app.orchestrator.CallState
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.app.orchestrator.Request
import dev.kestrel.app.orchestrator.WakeUpMode
import dev.kestrel.app.orchestrator.VoiceView
import dev.kestrel.app.orchestrator.Work
import dev.kestrel.commands.Command
import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.Direction
import dev.kestrel.commands.Outcome
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.InputIntent
import dev.kestrel.conversation.model.OwnerInput
import dev.kestrel.voice.Keyword
import dev.kestrel.voice.ModelsNotReadyException
import dev.kestrel.voice.VoiceChoice
import dev.kestrel.voice.VoiceOptions
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    private var settingsOpening = SettingsOpening.OPENED

    // While set, the settings' start waits for it, as Android's start may take up to 5 s.
    private var settingsHeld: CompletableDeferred<Unit>? = null

    // The screen's turn, which a goal's round takes in the app (design 6.5 of sub-project 7).
    private val turns = ScreenTurns({ what, error -> reports += what to error })
    private val conditions = FakeConditions()
    private val notices = FakeNotices()
    private val memory = FakeMemory()
    private val exits = FakeExits()
    private val calls = FakeCallMonitor()
    private val callActions = FakeCallControl()
    private val storedOptions = MutableStateFlow(ListeningOptions())

    // The texts that the bar keeps until they are released, and how often a kept text was released.
    private val kept = mutableListOf<String>()
    private var releases = 0
    private val bar = object : FeedbackBar {
        override fun show(text: String, keep: Boolean) {
            shown += text
            if (keep) kept += text
        }

        override fun release() {
            releases++
        }
    }
    private val runner = FakeRunner()
    private val commandReports = mutableListOf<CommandReport>()

    // How often a countdown of countDown() ran out, as a call would be placed then.
    private var placedCalls = 0

    // The wall clock, in milliseconds.
    private var now = 1_000_000L

    // The phone's clock of the timeline, in milliseconds, which the tests move by hand.
    private var elapsed = 50_000L
    private val timeline = Timeline { elapsed }

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
        // The voice channel drops the spoken outputs that waited for their turn (decision 10 of sub-project 7).
        assertEquals(1L, listening.voicePath.speechStops)
    }

    @Test
    fun aSampleUsesTheSessionsVoiceOnlyWhenItSpeaksWithTheChosenOptions() = runTest {
        val listening = listeningWithSession()
        val session = sessions.opened.single()
        val kokoro = VoiceOptions(speed = 1.2f, voice = VoiceChoice.KOKORO)
        session.voice = kokoro

        // Another speed or voice needs a voice of its own.
        assertNull(listening.speakWithSessionVoice(kokoro.copy(speed = 1f), "A sample."))
        val spoken = async { listening.speakWithSessionVoice(kokoro, "A sample.") }
        runCurrent()
        assertEquals(listOf("A sample."), session.said)
        session.replies.single().complete(Spoke.FINISHED)
        assertEquals(Spoke.FINISHED, spoken.await())
        listening.send(Event.TurnOff)
        runCurrent()
        assertNull(listening.speakWithSessionVoice(kokoro, "A sample."))
    }

    @Test
    fun aSampleStopsWhenThePageThatAskedForItIsLeft() = runTest {
        val listening = listeningWithSession()
        val session = sessions.opened.single()
        val piper = VoiceOptions()
        session.voice = piper

        val page = launch { listening.speakWithSessionVoice(piper, "A sample.") }
        runCurrent()
        page.cancel()
        runCurrent()

        // Only the sample stops, not a reply that the Orchestrator may have queued.
        assertTrue(session.replies.single().isCancelled)
        assertTrue(log.none { it == "stop speaking" })
    }

    @Test
    fun theKeywordHitsOfTheOpenSessionAreCounted() = runTest {
        val listening = listeningWithSession()
        val events = sessions.opened.single().listener

        events.send(Event.Spotted(Keyword.KESTREL))
        events.send(Event.Spotted(Keyword.STOP))
        // Events that reach the Orchestrator another way are not the spotter's.
        listening.send(Event.Spotted(Keyword.STOP))
        events.send(Event.Heard("Kestrel", named = true))
        runCurrent()
        listening.send(Event.TurnOff)
        runCurrent()
        // A closed session's late hit does not count.
        events.send(Event.Spotted(Keyword.KESTREL))

        assertEquals(2, listening.keywordHits)
    }

    @Test
    fun aCommandReachesTheConversationAsTheVoicesInput() = runTest {
        val listening = listeningWithSession()
        val inputs = mutableListOf<OwnerInput>()
        listening.converse = { inputs += it }

        listening.send(Event.Heard("Kestrel, open the settings.", named = true))
        runCurrent()

        assertEquals(InputChannel.VOICE, inputs.single().channel)
        assertEquals(InputIntent.Command("open the settings."), inputs.single().intent)
        assertEquals(emptyList<String>(), sessions.opened.single().said)
        assertEquals(Work.IDLE, listening.state.value.work)
    }

    @Test
    fun theVoicePathReturnsOnceItsWordsHaveBeenSpokenOrWhileOffShown() = runTest {
        val listening = listeningWithSession()
        val session = sessions.opened.single()

        val said = async { listening.voicePath.say("Done.") }
        runCurrent()
        assertEquals(listOf("Done."), session.said)
        assertEquals("Done.", shown.last())
        assertFalse(said.isCompleted)
        session.replies.single().complete(Spoke.FINISHED)
        runCurrent()
        assertTrue(said.isCompleted)

        listening.send(Event.TurnOff)
        runCurrent()
        val shownOnly = async { listening.voicePath.say("Later.") }
        runCurrent()
        assertTrue(shownOnly.isCompleted)
        assertEquals("Later.", shown.last())
    }

    @Test
    fun theFollowUpWindowCountsItsSecondsDownOnTheBarAndFadesAtItsEnd() = runTest {
        val listening = listeningWithSession()
        val session = sessions.opened.single()
        listening.send(Event.ConversationChanged(VoiceView(open = true)))
        listening.send(Event.FollowUpLength(3_000))

        val said = async { listening.voicePath.say("Done.", final = true) }
        runCurrent()
        session.replies.single().complete(Spoke.FINISHED)
        runCurrent()
        assertTrue(said.isCompleted)
        assertEquals(ListeningTexts.goOn(3), kept.last())
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(ListeningTexts.goOn(2), kept.last())

        val before = releases
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(Work.IDLE, listening.state.value.work)
        assertEquals(before + 1, releases)
    }

    @Test
    fun aFinalReplyThatAndroidRefusedOrInterruptedOpensNoWindowAndStaysOnTheBar() = runTest {
        val listening = listeningWithSession()
        val session = sessions.opened.single()
        listening.send(Event.ConversationChanged(VoiceView(open = true)))

        // As when another app takes the audio focus (section 14.6): the owner did not hear the reply.
        for (spoke in listOf(Spoke.INTERRUPTED, Spoke.REFUSED)) {
            val said = async { listening.voicePath.say("Done.", final = true) }
            runCurrent()
            session.replies.last().complete(spoke)
            runCurrent()
            assertTrue(said.isCompleted)
            assertEquals(Work.IDLE, listening.state.value.work)
            assertEquals("Done.", shown.last())
        }
        assertEquals(emptyList<String>(), kept)
    }

    @Test
    fun aFinalReplyWithoutAVoiceSessionOpensNoWindowAndStaysOnTheBar() = runTest {
        val listening = listening()
        listening.send(Event.Toggle)
        listening.send(Event.ConversationChanged(VoiceView(open = true)))
        runCurrent()

        // The service has not reported the foreground yet, so no session is open.
        val said = async { listening.voicePath.say("Done.", final = true) }
        runCurrent()
        assertTrue(said.isCompleted)
        assertEquals(Work.IDLE, listening.state.value.work)
        assertEquals("Done.", shown.last())
        assertEquals(emptyList<String>(), kept)
    }

    @Test
    fun theCountdownNeverTakesTheBarFromAnotherTextAndReleasesOnlyItsOwn() = runTest {
        val listening = listeningWithSession()
        val session = sessions.opened.single()
        listening.send(Event.ConversationChanged(VoiceView(open = true)))
        listening.send(Event.FollowUpLength(3_000))

        // "Microphone blocked" stays: it ends the window at once (re-check of Task 4, N1), the countdown stops, and the
        // window's end releases nothing.
        val said = async { listening.voicePath.say("Done.", final = true) }
        runCurrent()
        session.replies.last().complete(Spoke.FINISHED)
        runCurrent()
        assertTrue(said.isCompleted)
        assertEquals(ListeningTexts.goOn(3), shown.last())
        listening.send(Event.MicBusy(true))
        runCurrent()
        assertEquals(ListeningTexts.MIC_BUSY, shown.last())
        assertEquals(Work.IDLE, listening.state.value.work)
        advanceTimeBy(3_001)
        runCurrent()
        assertEquals(ListeningTexts.MIC_BUSY, shown.last())
        assertEquals(0, releases)
        listening.send(Event.MicBusy(false))

        // A question that ends the window stays kept until the voice channel lets it go.
        val again = async { listening.voicePath.say("Sent.", final = true) }
        runCurrent()
        session.replies.last().complete(Spoke.FINISHED)
        runCurrent()
        assertTrue(again.isCompleted)
        assertEquals(ListeningTexts.goOn(3), shown.last())
        listening.send(Event.ChannelAsks(4, "Shall I send it?", QuestionKind.CONFIRM))
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals("Shall I send it?", shown.last())
        assertEquals(0, releases)
    }

    @Test
    fun theWaitForACommandAfterYesReachesTheConversation() = runTest {
        val listening = listeningWithSession()
        val awaiting = mutableListOf<Boolean>()
        listening.awaitingCommand = { awaiting += it }

        listening.send(Event.Heard("Kestrel", named = true))
        runCurrent()
        listening.send(Event.Heard("Open Viber.", named = false))
        runCurrent()

        assertEquals(listOf(true, false), awaiting.dropWhile { !it })
    }

    @Test
    fun aReplyThatCannotBeSpokenIsShownOnTheBar() = runTest {
        val listening = listeningWithSession()
        val session = sessions.opened.single()
        listening.send(Event.SettingsUnavailable)
        runCurrent()
        shown.clear()

        session.replies[0].complete(Spoke.REFUSED)
        runCurrent()
        listening.send(Event.SettingsUnavailable)
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
            listOf(ListeningTexts.SETTINGS_NEED_SERVICE, ListeningTexts.SETTINGS_NEED_SERVICE, "Kestrel", ListeningTexts.YES),
            shown,
        )
        assertEquals(listOf("A reply", "A reply"), reports.map { it.first })
        assertFalse(listening.state.value.speaking)
    }

    @Test
    fun aReplyThatAndroidInterruptsIsShownOnTheBar() = runTest {
        val listening = listeningWithSession()
        val session = sessions.opened.single()
        listening.send(Event.SettingsUnavailable)
        runCurrent()
        shown.clear()

        // Android took the audio focus away, for a call or an app that asks for exclusive focus (section 14.6).
        session.replies.single().complete(Spoke.INTERRUPTED)
        runCurrent()

        assertEquals(listOf(ListeningTexts.SETTINGS_NEED_SERVICE), shown)
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

        // Any other reply goes to the bar, because there is no voice (section 14.6).
        listening.send(Event.SettingsUnavailable)
        runCurrent()
        assertEquals(ListeningTexts.SETTINGS_NEED_SERVICE, shown.last())
    }

    @Test
    fun aCommandRunsAsOneJobAndReportsItsEnd() = runTest {
        val listening = listeningWithSession()
        collectReports(listening)

        listening.send(Event.Heard("Scroll down", named = false))
        runCurrent()

        assertEquals(listOf<Command>(Command.Scroll(Direction.DOWN, null)), runner.started)
        assertEquals(listOf("Scroll down"), runner.heard)
        assertEquals(Work.IDLE, listening.state.value.work)
        assertEquals(Command.Scroll(Direction.DOWN, null), listening.state.value.lastCommand)
        assertEquals(listOf(CommandReport(1, "Scroll", Outcome.DONE)), commandReports)
        assertEquals(RecentCommands.Entry(1, "Scroll", Outcome.DONE), listening.recentCommand(1))
    }

    @Test
    fun theRecentCommandsAnswerForEachNumberAlsoAfterANewerOne() = runTest {
        val listening = listeningWithSession()
        val gate = CompletableDeferred<Unit>()
        runner.action = {
            gate.await()
            CommandResult(Outcome.DONE)
        }
        listening.send(Event.Heard("Scroll down", named = false))
        runCurrent()
        assertEquals(RecentCommands.Entry(1, "Scroll", null), listening.recentCommand(1))

        gate.complete(Unit)
        runCurrent()
        runner.action = { CommandResult(Outcome.IGNORED) }
        listening.send(Event.Heard("Go home", named = false))
        runCurrent()

        // A check of the A71 sessions asks for its own command, number 1, after another utterance started number 2.
        assertEquals(RecentCommands.Entry(1, "Scroll", Outcome.DONE), listening.recentCommand(1))
        assertEquals(RecentCommands.Entry(2, "Global", Outcome.IGNORED), listening.recentCommand(2))
        assertNull(listening.recentCommand(3))
    }

    @Test
    fun aCommandKnowsWhenTheOwnerBeganToSayIt() = runTest {
        val listening = listeningWithSession()
        var spokeAt: Long? = null
        runner.action = { conversation ->
            spokeAt = conversation.spokeAt
            CommandResult(Outcome.DONE)
        }

        listening.send(Event.Heard("Tap 5", named = false, spokeAt = 4_321L))
        runCurrent()

        assertEquals(4_321L, spokeAt)
    }

    @Test
    fun aNewCommandWaitsUntilTheCancelledOneHasCompleted() = runTest {
        val listening = listeningWithSession()
        collectReports(listening)
        val gate = CompletableDeferred<Unit>()
        // Like a gesture or a binder call that Android finishes, the first command ignores its cancellation for a while.
        runner.action = {
            withContext(NonCancellable) { gate.await() }
            CommandResult(Outcome.DONE)
        }
        listening.send(Event.Heard("Scroll down", named = false))
        runCurrent()

        runner.action = { CommandResult(Outcome.DONE) }
        listening.send(Event.Heard("Stop.", named = false))
        listening.send(Event.Heard("Scroll up", named = false))
        runCurrent()
        assertEquals(1, runner.started.size)

        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf<Command>(Command.Scroll(Direction.DOWN, null), Command.Scroll(Direction.UP, null)), runner.started)
        assertEquals(listOf(Outcome.CANCELLED, Outcome.DONE), commandReports.map { it.outcome })
    }

    // Whole-plan re-review of plan 7D, N1, and its round 2: a goal's round that takes the screen's turn for an owner's input
    // that came after the instant command started has the Orchestrator cancel the command, and gets the screen only once
    // the command's job has really ended.
    @Test
    fun aRoundThatTakesTheScreenForANewerInputHasTheRunningCommandCancelledAndWaitsForItsJob() = runTest {
        val listening = listeningWithSession()
        collectReports(listening)
        val gate = CompletableDeferred<Unit>()
        runner.action = {
            withContext(NonCancellable) { gate.await() }
            CommandResult(Outcome.DONE)
        }
        listening.send(Event.Heard("Scroll down", named = false))
        runCurrent()
        assertEquals(Work.RUNNING_COMMAND, listening.state.value.work)

        val taken = async { turns.take(after = InputIds.next()) }
        runCurrent()
        assertEquals(Work.IDLE, listening.state.value.work)
        assertEquals(ListeningTexts.CANCELLED, shown.last())
        assertFalse(taken.isCompleted)
        gate.complete(Unit)
        runCurrent()

        assertTrue(taken.await())
        assertEquals(listOf(Outcome.CANCELLED), commandReports.map { it.outcome })
    }

    // Re-review round 2, R2-1: a round that goes on by itself, as after a hand-over that ended by itself, never cancels the
    // owner's command, also one that he started before the round's own input; it waits until the command has ended.
    @Test
    fun aRoundThatGoesOnByItselfWaitsForTheOwnersCommandWithoutCancellingIt() = runTest {
        val listening = listeningWithSession()
        collectReports(listening)
        val ended = CompletableDeferred<Unit>()
        runner.action = {
            ended.await()
            CommandResult(Outcome.DONE)
        }
        val before = InputIds.next()
        listening.send(Event.Heard("Scroll down", named = false))
        runCurrent()

        val taken = async { turns.take(after = before) }
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(Work.RUNNING_COMMAND, listening.state.value.work)
        assertFalse(taken.isCompleted)
        ended.complete(Unit)
        runCurrent()

        assertTrue(taken.await())
        assertEquals(listOf(Outcome.DONE), commandReports.map { it.outcome })
    }

    @Test
    fun aStuckCancelledCommandHoldsTheNextOneForAtMostThreeSeconds() = runTest {
        val listening = listeningWithSession()
        val gate = CompletableDeferred<Unit>()
        runner.action = {
            withContext(NonCancellable) { gate.await() }
            CommandResult(Outcome.DONE)
        }
        listening.send(Event.Heard("Scroll down", named = false))
        runCurrent()
        runner.action = { CommandResult(Outcome.DONE) }
        listening.send(Event.Heard("Stop.", named = false))
        listening.send(Event.Heard("Scroll up", named = false))
        runCurrent()

        advanceTimeBy(2_999)
        runCurrent()
        assertEquals(1, runner.started.size)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(Command.Scroll(Direction.UP, null), runner.started.last())
        assertEquals("A cancelled instant command", reports.last().first)
        // Kestrel goes on without the stuck job: the next command starts at once.
        listening.send(Event.Heard("Scroll down", named = false))
        runCurrent()
        assertEquals(3, runner.started.size)
        gate.complete(Unit)
        runCurrent()
    }

    @Test
    fun aStuckCommandThatEndsLateLeavesTheTextThatANewerCommandKeeps() = runTest {
        val listening = listeningWithSession()
        val gate = CompletableDeferred<Unit>()
        runner.action = {
            withContext(NonCancellable) { gate.await() }
            CommandResult(Outcome.DONE)
        }
        listening.send(Event.Heard("Scroll down", named = false))
        runCurrent()
        runner.action = { conversation ->
            conversation.ask("Which one?")
            CommandResult(Outcome.DONE)
        }
        listening.send(Event.Heard("Stop.", named = false))
        listening.send(Event.Heard("tap Delete", named = false))
        runCurrent()
        advanceTimeBy(3_001)
        runCurrent()
        assertTrue(listening.state.value.asking)
        assertEquals(listOf("Which one?"), kept)

        // The stuck job completes while the newer command still waits for its answer: the question stays kept.
        gate.complete(Unit)
        runCurrent()
        assertEquals(0, releases)

        sessions.opened.single().replies.last().complete(Spoke.FINISHED)
        listening.send(Event.Heard("Two.", named = false))
        runCurrent()
        assertEquals(Work.IDLE, listening.state.value.work)
        // The answer took the question's place on the bar, so the newer command's end has no text of its own to release.
        assertEquals(0, releases)
    }

    @Test
    fun aCommandReachesTheConversationAtOnceWhileTheCancelledCommandStillHoldsTheScreen() = runTest {
        val listening = listeningWithSession()
        val gate = CompletableDeferred<Unit>()
        runner.action = {
            withContext(NonCancellable) { gate.await() }
            CommandResult(Outcome.DONE)
        }
        listening.send(Event.Heard("Scroll down", named = false))
        runCurrent()
        val inputs = mutableListOf<InputIntent>()
        listening.converse = { inputs += it.intent }

        // The request cancels the command first; the goal's round takes the screen's turn itself (design 7.1 of
        // sub-project 7), so the input does not wait for the command's job.
        listening.send(Event.Heard("Kestrel, open Viber.", named = true))
        runCurrent()
        assertEquals(listOf<InputIntent>(InputIntent.Command("open Viber.")), inputs)

        gate.complete(Unit)
        runCurrent()
        assertEquals(1, inputs.size)
    }

    @Test
    fun theConversationsQuestionAndHandOverThatCannotBeSpokenStayKeptOnTheBar() = runTest {
        val listening = listeningWithSession()
        val session = sessions.opened.single()
        listening.send(Event.Screen(on = true))
        runCurrent()

        listening.send(Event.ChannelAsks(4, "Shall I send it?", QuestionKind.CONFIRM))
        runCurrent()
        var before = shown.size
        session.replies.last().complete(Spoke.REFUSED)
        runCurrent()
        assertEquals(before, shown.size)

        listening.send(Event.ChannelHandsOver("Please pay yourself, then say 'Kestrel, continue'."))
        runCurrent()
        before = shown.size
        session.replies.last().complete(Spoke.INTERRUPTED)
        runCurrent()
        assertEquals(before, shown.size)
        assertEquals(listOf("Shall I send it?", "Please pay yourself, then say 'Kestrel, continue'."), kept)
        // The voice channel lets them go when they end (VoiceChannelTest).
        assertEquals(0, releases)
    }

    @Test
    fun theSettingsWaitUntilTheCancelledCommandHasCompleted() = runTest {
        val listening = listeningWithSession()
        val gate = CompletableDeferred<Unit>()
        runner.action = {
            withContext(NonCancellable) { gate.await() }
            CommandResult(Outcome.DONE)
        }
        listening.send(Event.Heard("Scroll down", named = false))
        runCurrent()
        log.clear()

        // The request cancels the command first; the settings open only once its job has completed.
        listening.send(Event.Heard("Kestrel, open settings.", named = true))
        runCurrent()
        assertFalse("open settings" in log)

        gate.complete(Unit)
        runCurrent()
        assertTrue("open settings" in log)
    }

    @Test
    fun sayReturnsAtOnceWhenTheReplyCannotStart() = runTest {
        val listening = listeningWithSession()
        sessions.opened.single().speakFailure = IllegalStateException("No voice")
        var said = false
        runner.action = { conversation ->
            conversation.say("Calling 112.")
            said = true
            CommandResult(Outcome.DONE)
        }

        listening.send(Event.Heard("tap Send", named = false))
        runCurrent()

        assertTrue(said)
        assertEquals(Work.IDLE, listening.state.value.work)
    }

    @Test
    fun aRefusedQuestionStaysOnTheBarAsTheKeptText() = runTest {
        val listening = listeningWithSession()
        runner.action = { conversation ->
            conversation.ask("Which one?")
            CommandResult(Outcome.CANCELLED)
        }
        listening.send(Event.Heard("tap Delete", named = false))
        runCurrent()
        val before = shown.size

        // Android refuses the question's reply: the bar keeps the question, and shows no copy of it that would fade.
        sessions.opened.single().replies.last().complete(Spoke.REFUSED)
        runCurrent()

        assertEquals(before, shown.size)
        assertEquals(listOf("Which one?"), kept)
    }

    @Test
    fun aCommandCancelledBeforeItStartedNeverRuns() = runTest {
        val listening = listeningWithSession()
        collectReports(listening)
        val gate = CompletableDeferred<Unit>()
        runner.action = {
            withContext(NonCancellable) { gate.await() }
            CommandResult(Outcome.DONE)
        }
        listening.send(Event.Heard("Scroll down", named = false))
        runCurrent()
        listening.send(Event.Heard("Stop.", named = false))
        listening.send(Event.Heard("Scroll up", named = false))
        listening.send(Event.Heard("Stop.", named = false))
        runCurrent()

        gate.complete(Unit)
        runCurrent()

        assertEquals(1, runner.started.size)
        assertEquals(listOf(CommandReport(1, "Scroll", Outcome.CANCELLED), CommandReport(2, "Scroll", Outcome.CANCELLED)), commandReports)
        assertEquals(Work.IDLE, listening.state.value.work)
    }

    @Test
    fun aQuestionIsSpokenAndTheNextTranscriptIsItsAnswer() = runTest {
        val listening = listeningWithSession()
        val session = sessions.opened.single()
        var answer: String? = "none"
        runner.action = { conversation ->
            answer = conversation.ask("Which one?")
            CommandResult(Outcome.DONE)
        }
        listening.send(Event.Heard("tap Send", named = false))
        runCurrent()
        assertEquals(listOf("Which one?"), session.said)
        assertEquals(listOf("Which one?"), kept)
        assertTrue(listening.state.value.asking)

        session.replies.single().complete(Spoke.FINISHED)
        listening.send(Event.Heard("Number two.", named = false))
        runCurrent()

        assertEquals("Number two.", answer)
        assertEquals(Work.IDLE, listening.state.value.work)
        // The answer took the kept question's place on the bar, so the command's end has no text of its own to release.
        assertEquals("Number two.", shown.last())
        assertEquals(0, releases)
    }

    @Test
    fun aQuestionWithoutAnAnswerEndsFifteenSecondsAfterItWasSpoken() = runTest {
        val listening = listeningWithSession()
        val session = sessions.opened.single()
        var answer: String? = "none"
        runner.action = { conversation ->
            answer = conversation.ask("Which one?")
            CommandResult(if (answer == null) Outcome.CANCELLED else Outcome.DONE)
        }
        listening.send(Event.Heard("tap Send", named = false))
        runCurrent()
        advanceTimeBy(3_000)
        session.replies.single().complete(Spoke.FINISHED)
        runCurrent()

        advanceTimeBy(14_999)
        runCurrent()
        assertEquals("none", answer)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(null, answer)
        assertEquals(ListeningTexts.CANCELLED, shown.last())
    }

    @Test
    fun aNumberSoonAfterAnUnansweredQuestionAsksForTheCommandAgainAndLaterRuns() = runTest {
        val listening = listeningWithSession()
        val session = sessions.opened.single()
        var commands = 0
        runner.action = { conversation ->
            commands++
            conversation.ask("Which one?")
            CommandResult(Outcome.CANCELLED)
        }
        listening.send(Event.Heard("tap Send", named = false))
        runCurrent()
        session.replies.single().complete(Spoke.FINISHED)
        advanceTimeBy(15_001)
        runCurrent()

        listening.send(Event.Heard("2", named = false))
        runCurrent()
        assertEquals("The question ended. Say the command again.", shown.last())
        assertEquals(1, commands)

        // 10 s after the question, a number is a new command.
        listening.send(Event.Heard("tap Send", named = false))
        runCurrent()
        session.replies.last().complete(Spoke.FINISHED)
        advanceTimeBy(15_001)
        runCurrent()
        advanceTimeBy(10_001)
        runCurrent()
        assertEquals(2, commands)
        runner.action = {
            commands++
            CommandResult(Outcome.DONE)
        }
        listening.send(Event.Heard("2", named = false))
        runCurrent()
        assertEquals(3, commands)
    }

    @Test
    fun sayReturnsWhenTheReplyHasEnded() = runTest {
        val listening = listeningWithSession()
        val session = sessions.opened.single()
        var said = false
        runner.action = { conversation ->
            conversation.say("Calling 112.")
            said = true
            CommandResult(Outcome.DONE)
        }
        listening.send(Event.Heard("tap Send", named = false))
        runCurrent()
        assertEquals(listOf("Calling 112."), session.said)
        assertEquals("Calling 112.", shown.last())
        assertFalse(said)

        // A reply that Android interrupted has ended too.
        session.replies.single().complete(Spoke.INTERRUPTED)
        runCurrent()
        assertTrue(said)
    }

    @Test
    fun theOwnersSpeechHoldsTheCountdownAndALateCancelStillStopsIt() = runTest {
        val listening = listeningWithSession()
        collectReports(listening)
        countDown()
        listening.send(Event.Heard("tap Send", named = false))
        runCurrent()
        assertEquals("Calling in 4", kept.last())

        advanceTimeBy(1_000)
        listening.send(Event.Speaking)
        runCurrent()
        // The transcript of "cancel" comes 5 s after the word, later than the countdown would have ended.
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(0, placedCalls)
        listening.send(Event.Heard("Cancel.", named = false))
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(0, placedCalls)
        assertEquals(Outcome.CANCELLED, commandReports.single().outcome)
        assertEquals(ListeningTexts.CANCELLED, shown.last())
    }

    @Test
    fun otherSpeechLetsTheCountdownGoOnFromWhereItStopped() = runTest {
        val listening = listeningWithSession()
        countDown()
        listening.send(Event.Heard("tap Send", named = false))
        runCurrent()
        advanceTimeBy(1_000)
        listening.send(Event.Speaking)
        runCurrent()
        advanceTimeBy(5_000)
        listening.send(Event.Heard("What time is it?", named = false))
        runCurrent()

        // 3 s of the countdown were left when the owner started to speak.
        advanceTimeBy(2_900)
        runCurrent()
        assertEquals(0, placedCalls)
        advanceTimeBy(400)
        runCurrent()
        assertEquals(1, placedCalls)
    }

    @Test
    fun theSpottersCancelStopsTheCountdownAtOnceWhileKestrelIsSilent() = runTest {
        val listening = listeningWithSession()
        collectReports(listening)
        countDown()
        listening.send(Event.Heard("tap Send", named = false))
        runCurrent()
        advanceTimeBy(1_000)

        listening.send(Event.Spotted(Keyword.CANCEL))
        runCurrent()

        assertEquals(Outcome.CANCELLED, commandReports.single().outcome)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(0, placedCalls)
    }

    @Test
    fun anUtteranceWithoutATranscriptLetsTheCountdownGoOn() = runTest {
        val listening = listeningWithSession()
        countDown()
        listening.send(Event.Heard("tap Send", named = false))
        runCurrent()
        advanceTimeBy(1_000)
        listening.send(Event.Speaking)
        runCurrent()
        advanceTimeBy(1_000)
        listening.send(Event.NothingHeard)
        runCurrent()

        advanceTimeBy(2_900)
        runCurrent()
        assertEquals(0, placedCalls)
        advanceTimeBy(400)
        runCurrent()
        assertEquals(1, placedCalls)
    }

    @Test
    fun thirtySecondsOfHoldsGiveTheCountdownUp() = runTest {
        val listening = listeningWithSession()
        collectReports(listening)
        countDown()
        listening.send(Event.Heard("tap Send", named = false))
        runCurrent()
        advanceTimeBy(1_000)
        // A television that keeps talking: the owner never seems to finish.
        listening.send(Event.Speaking)
        runCurrent()

        advanceTimeBy(29_800)
        runCurrent()
        assertTrue(commandReports.isEmpty())
        advanceTimeBy(400)
        runCurrent()

        assertEquals(0, placedCalls)
        assertEquals(Outcome.CANCELLED, commandReports.single().outcome)
        assertEquals(ListeningTexts.CANCELLED, shown.last())
    }

    @Test
    fun whileAPasswordFieldHasTheFocusTheBarHidesWhatKestrelHeard() = runTest {
        val listening = listeningWithSession()
        runner.password = true

        listening.send(Event.Heard("Hunter two", named = false))
        runCurrent()

        assertEquals(listOf(ListeningTexts.LISTENING, ListeningTexts.PRIVATE_INPUT), shown)
    }

    @Test
    fun aRunnerThatThrowsEndsTheCommandAsFailed() = runTest {
        val listening = listeningWithSession()
        collectReports(listening)
        runner.action = { throw IllegalStateException("The screen went away") }

        listening.send(Event.Heard("tap Send", named = false))
        runCurrent()

        assertEquals(Work.IDLE, listening.state.value.work)
        assertEquals(ListeningTexts.COMMAND_FAILED, shown.last())
        assertEquals("An instant command", reports.single().first)
        assertEquals(Outcome.FAILED, commandReports.single().outcome)
    }

    @Test
    fun aTextThatTheCommandKeepsIsReleasedWhenItEnds() = runTest {
        val listening = listeningWithSession()
        val gate = CompletableDeferred<Unit>()
        runner.action = { conversation ->
            conversation.keep("1, Ana. 2, Anja.")
            gate.await()
            CommandResult(Outcome.DONE)
        }
        listening.send(Event.Heard("tap Send", named = false))
        runCurrent()
        assertEquals(listOf("1, Ana. 2, Anja."), kept)
        assertEquals(0, releases)

        gate.complete(Unit)
        runCurrent()
        assertEquals(1, releases)
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
    // Fix-7d-n1 review, nit 1: the failure's text takes the bar through the Rules, so it ends a follow-up window that
    // opened while the settings started, as every other text does.
    @Test
    fun settingsThatFailToOpenEndTheFollowUpWindow() = runTest {
        settingsFailure = IllegalStateException("No activity")
        settingsHeld = CompletableDeferred()
        val listening = listeningWithSession()
        val session = sessions.opened.single()
        listening.send(Event.ConversationChanged(VoiceView(open = true)))
        listening.send(Event.Heard("Kestrel, open settings.", named = true))
        runCurrent()
        assertEquals(listOf("open settings"), log.filter { it == "open settings" })

        val said = async { listening.voicePath.say("Done.", final = true) }
        runCurrent()
        session.replies.last().complete(Spoke.FINISHED)
        runCurrent()
        assertTrue(said.isCompleted)
        assertEquals(Work.FOLLOWING_UP, listening.state.value.work)
        settingsHeld?.complete(Unit)
        runCurrent()

        assertEquals(ListeningTexts.CANNOT_OPEN_SETTINGS, shown.last())
        assertEquals(Work.IDLE, listening.state.value.work)
    }

    @Test
    fun kestrelOpenSettingsWithoutTheAccessibilityServiceSaysWhy() = runTest {
        // Android blocks the start from the background without an exception, and the bar does not exist.
        settingsOpening = SettingsOpening.NEED_SERVICE
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
    fun settingsThatAndroidKeptBackAreSaidAndMarked() = runTest {
        // With only the assistant role, Android 13 may keep the settings back without an exception (A71 session of
        // sub-project 2, item 5); AppGraph then has posted the notification that opens them.
        settingsOpening = SettingsOpening.BLOCKED
        val listening = listeningWithSession()
        log.clear()

        listening.send(Event.Heard("Kestrel, open settings.", named = true))
        runCurrent()

        assertEquals(listOf("open settings", "recognition NONE"), log)
        assertEquals(listOf(ListeningTexts.SETTINGS_BLOCKED), sessions.opened.single().said)
        assertEquals("settings-blocked", timeline.reacted?.kind)
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
    fun theTimelineMarksTheSessionTheReactionToSpeechAndAStop() = runTest {
        val listening = listening()
        listening.send(Event.Toggle)
        runCurrent()
        elapsed += 6_000
        listening.onForeground(service)
        runCurrent()
        assertEquals(56_000L, timeline.opened)

        // The owner's "Kestrel" ended 700 ms before Kestrel began to wait for the task.
        elapsed += 700
        sessions.opened.single().listener.send(Event.Heard("Kestrel", named = true, seconds = 0.6f, endedAt = 56_000L))
        runCurrent()
        assertEquals(Timeline.Mark("wait", 56_700L, sinceSpeech = 700L), timeline.reacted)

        conditions.restricted = true
        conditions.demoted = true
        elapsed += 1_000
        listening.send(Event.MicBusy(true))
        runCurrent()
        assertEquals(Timeline.Mark("BATTERY_RESTRICTED", 57_700L), timeline.stopped)
    }

    @Test
    fun theCountsCountHitsUtterancesAndReadsButKeepNoWords() = runTest {
        val listening = listeningWithSession()
        val events = sessions.opened.single().listener

        events.send(Event.Spotted(Keyword.KESTREL))
        events.send(Event.Spotted(Keyword.STOP))
        events.send(Event.Heard("Kestrel, wake up.", named = true, seconds = 1.25f))
        events.send(Event.Heard("", named = false, seconds = 0.5f))
        runCurrent()

        assertEquals(
            "audioMillis=1750 hit-kestrel=1 hit-stop=1 named=1 read-empty=1 read-wakeup=1 utterances=2",
            listening.voiceCounts.describe(),
        )
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
        val inputs = mutableListOf<OwnerInput>()
        listening.converse = { inputs += it }
        listening.send(Event.TurnOff)
        runCurrent()
        listening.send(Event.Toggle)
        runCurrent()

        old.send(Event.Heard("Kestrel, open the settings.", named = true))
        runCurrent()

        assertEquals(emptyList<OwnerInput>(), inputs)
    }

    @Test
    fun anEventThatAClosingSessionSendsAsKestrelTurnsOffNeverActsOnTheNextState() = runTest {
        val listening = listeningWithSession()
        val old = sessions.opened.single().listener
        val inputs = mutableListOf<OwnerInput>()
        listening.converse = { inputs += it }

        // The owner turns Kestrel off and on again, and the old session's transcript arrives before the controller has
        // handled either: it must not reach the conversation in the new state.
        listening.send(Event.TurnOff)
        listening.send(Event.Toggle)
        old.send(Event.Heard("Kestrel, open the settings.", named = true))
        runCurrent()

        assertEquals(Mode.LISTENING, listening.state.value.mode)
        assertEquals(emptyList<OwnerInput>(), inputs)
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
        listening.converse = { launch { listening.voicePath.say("Opened.") } }
        listening.send(Event.Heard("Kestrel", named = true))
        runCurrent()
        // A transcript that arrives while "Yes?" still plays goes to the conversation, whose reply queues behind "Yes?".
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

    @Test
    fun theCallStateIsWatchedWhileASessionIsOpen() = runTest {
        val listening = listeningWithSession()
        assertEquals(1, calls.starts)

        calls.change(CallState.RINGING)
        runCurrent()
        assertEquals(Mode.RINGING, listening.state.value.mode)

        listening.send(Event.TurnOff)
        runCurrent()
        assertEquals(1, calls.stops)
        assertEquals(Mode.OFF, listening.state.value.mode)
    }

    @Test
    fun aCallIsNeverStoredButTheModeThatItInterrupted() = runTest {
        val listening = listeningWithSession()
        listening.send(Event.Pause)
        runCurrent()

        calls.change(CallState.RINGING)
        runCurrent()
        calls.change(CallState.ACTIVE)
        runCurrent()
        // The owner chooses listening for after the call, with the tile.
        listening.send(Event.Toggle)
        runCurrent()
        calls.change(CallState.IDLE)
        runCurrent()

        assertEquals(Mode.LISTENING, listening.state.value.mode)
        // Section 8.5: a restart during the call brings back the mode that comes after it.
        assertEquals(listOf(Mode.LISTENING, Mode.PAUSED, Mode.LISTENING), memory.writes)
        assertEquals(
            listOf(
                ListeningTexts.LISTENING,
                ListeningTexts.PAUSED,
                ListeningTexts.RINGING,
                ListeningTexts.IN_CALL,
                ListeningTexts.LISTENING,
                ListeningTexts.LISTENING,
            ),
            shown,
        )
    }

    @Test
    fun theMicrophoneRecordsWhileACallRingsOnTheLockScreen() = runTest {
        val listening = listeningWithSession()
        listening.send(Event.Screen(on = false))
        runCurrent()
        log.clear()

        calls.change(CallState.RINGING)
        runCurrent()
        // Section 8.5: a ringing call is the one exception to the rule that Kestrel records only while the phone is
        // unlocked.
        assertEquals(listOf("microphone on"), log)

        calls.change(CallState.IDLE)
        runCurrent()
        assertEquals(listOf("microphone on", "microphone off"), log)
        assertEquals(Mode.LISTENING, listening.state.value.mode)
    }

    @Test
    fun aCallStateThatCannotBeWatchedIsReportedAndKestrelListensOn() = runTest {
        calls.failure = SecurityException("No permission to read the phone state")
        val listening = listeningWithSession()

        assertEquals("Watching the call state", reports.single().first)
        assertEquals(Mode.LISTENING, listening.state.value.mode)
        assertTrue(listening.sessionOpen.value)
    }

    @Test
    fun aReplyThatACallStopsIsShownOnTheBar() = runTest {
        val listening = listeningWithSession()
        val session = sessions.opened.single()
        listening.send(Event.SettingsUnavailable)
        runCurrent()
        shown.clear()

        calls.change(CallState.RINGING)
        runCurrent()
        // The call's StopSaying ended the reply before Android took the audio focus away (prototype P3).
        session.replies.single().complete(Spoke.STOPPED)
        runCurrent()

        // Section 14.6: the reply's text goes to the bar after the call's mode.
        assertEquals(listOf(ListeningTexts.RINGING, ListeningTexts.SETTINGS_NEED_SERVICE), shown)
    }

    @Test
    fun aCallCommandIsCarriedOutAndAFailureIsShownOnTheBar() = runTest {
        val listening = listeningWithSession()
        calls.change(CallState.RINGING)
        runCurrent()
        shown.clear()

        listening.send(Event.Heard("Answer.", named = false))
        runCurrent()
        assertEquals(listOf<Request.Call>(Request.Answer(speaker = false)), callActions.commands)
        assertEquals(listOf("Answer."), shown)

        callActions.result = ListeningTexts.CANNOT_DECLINE
        listening.send(Event.Heard("Decline.", named = false))
        runCurrent()
        // Section 14: Kestrel says why a command failed.
        assertEquals(listOf("Answer.", "Decline.", ListeningTexts.CANNOT_DECLINE), shown)
    }

    @Test
    fun aCallCommandThatThrowsIsReportedAndShown() = runTest {
        val listening = listeningWithSession()
        calls.change(CallState.RINGING)
        runCurrent()
        callActions.failure = SecurityException("No permission to answer calls")

        listening.send(Event.Heard("Answer.", named = false))
        runCurrent()

        assertEquals("A call command", reports.single().first)
        assertEquals(ListeningTexts.CALL_FAILED, shown.last())
    }

    @Test
    fun aPermissionGivenWhileKestrelListensStartsTheCallState() = runTest {
        val listening = listeningWithSession()

        listening.watchCalls()
        runCurrent()
        assertEquals(2, calls.starts)

        // Without a session, the next start watches the call state.
        listening.send(Event.TurnOff)
        runCurrent()
        listening.watchCalls()
        runCurrent()
        assertEquals(2, calls.starts)
    }

    @Test
    fun aCallReportThatComesAfterTheSessionClosedIsDropped() = runTest {
        val listening = listeningWithSession()
        listening.send(Event.TurnOff)
        runCurrent()
        listening.send(Event.Toggle)
        runCurrent()
        listening.onForeground(service)
        runCurrent()

        // Android's listener for the closed session reports a call that was on its way while the session closed.
        calls.given.first().invoke(CallState.RINGING)
        runCurrent()

        assertEquals(2, calls.given.size)
        assertEquals(Mode.LISTENING, listening.state.value.mode)
    }

    @Test
    fun aCallStateThatCannotBeUnwatchedIsReportedAndKestrelStillTurnsOff() = runTest {
        calls.stopFailure = IllegalStateException("The telephony service has gone")
        val listening = listeningWithSession()
        log.clear()

        listening.send(Event.TurnOff)
        runCurrent()

        assertEquals("Watching the call state", reports.single().first)
        assertEquals(listOf("close", "closed", "stop"), log)
        assertFalse(listening.sessionOpen.value)
        assertEquals(Mode.OFF, listening.state.value.mode)
    }

    @Test
    fun anAssistWhileKestrelIsOffAsksOnceTheSessionIsOpen() = runTest {
        val listening = listening()

        listening.assist()
        runCurrent()
        // Kestrel starts; "Yes?" waits for the voice session, so that it can be spoken and the wait counts from it.
        assertEquals(Mode.LISTENING, listening.state.value.mode)
        assertEquals(Work.IDLE, listening.state.value.work)

        listening.onForeground(service)
        runCurrent()
        assertEquals(listOf(ListeningTexts.YES), sessions.opened.single().said)
        assertEquals(Work.AWAITING_TASK, listening.state.value.work)
    }

    @Test
    fun anAssistWithASessionAsksAtOnceAndOneWhoseStartFailedIsForgotten() = runTest {
        val listening = listeningWithSession()
        listening.send(Event.Pause)
        runCurrent()

        listening.assist()
        runCurrent()
        assertEquals(Mode.LISTENING, listening.state.value.mode)
        assertEquals(listOf(ListeningTexts.YES), sessions.opened.single().said)

        listening.send(Event.TurnOff)
        runCurrent()
        services.failure = IllegalStateException("Not allowed in the background")
        listening.assist()
        runCurrent()
        assertEquals(Mode.OFF, listening.state.value.mode)
        services.failure = null
        listening.send(Event.Toggle)
        runCurrent()
        listening.onForeground(service)
        runCurrent()

        // The next session does not ask for the task of the assist whose start failed.
        assertTrue(sessions.opened[1].said.isEmpty())
        assertEquals(Work.IDLE, listening.state.value.work)
    }

    @Test
    fun anAssistWhoseSessionCannotStartTheMicrophoneLeavesKestrelOff() = runTest {
        sessions.microphoneFailure = SecurityException("Kestrel may not record audio")
        val listening = listening()

        listening.assist()
        runCurrent()
        listening.onForeground(service)
        runCurrent()

        // The microphone's failure turns Kestrel off before the Assist's turn comes, and the Assist does not start it
        // again.
        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(1, log.count { it == "start" })
    }

    @Test
    fun theAssistantServiceRestoresTheStoredModeOnceWithTheAccessibilityService() = runTest {
        memory.stored = Mode.LISTENING
        val listening = listening()

        listening.onAssistantServiceReady()
        runCurrent()
        assertEquals(Mode.LISTENING, listening.state.value.mode)
        // The accessibility service that connects later in the same process finds the restore done.
        listening.onAccessibilityServiceConnected()
        runCurrent()

        assertEquals(listOf("start"), log)
        assertEquals(1, memory.reads)
    }

    @Test
    fun theStoredOptionsReachTheOrchestratorWhenTheyChange() = runTest {
        val listening = listeningWithSession()

        storedOptions.value = ListeningOptions(WakeUpMode.LISTEN, pauseAfterSilence = true, callCommands = true, earbudMicrophone = true)
        runCurrent()

        val state = listening.state.value
        assertEquals(WakeUpMode.LISTEN, state.onWakeUp)
        assertTrue(state.pauseAfterSilence)
        assertTrue(state.callCommands)
        assertEquals(storedOptions.value, listening.currentOptions.value)
    }

    @Test
    fun optionsThatCannotBeReadAreReportedAndTheDefaultsStay() = runTest {
        val listening = listening(options = flow { throw IOException("The settings cannot be read") })
        runCurrent()

        assertEquals("Reading the listening options", reports.single().first)
        assertEquals(ListeningOptions(), listening.currentOptions.value)
    }

    @Test
    fun aStartDuringTheCloseOnTheLockScreenDoesNotRecord() = runTest {
        val listening = listeningWithSession()
        listening.send(Event.Screen(on = false))
        runCurrent()
        val closing = CompletableDeferred<Unit>()
        sessions.opened.single().closing = closing
        listening.send(Event.TurnOff)
        runCurrent()

        // The tile on the lock screen while the session closes: the start goes to the service that still runs, which
        // reports the screen only when it is created and when the screen changes.
        listening.send(Event.Toggle)
        runCurrent()
        listening.onForeground(service)
        closing.complete(Unit)
        runCurrent()

        // Section 8.5: Kestrel records on the lock screen only while a call rings.
        assertTrue(listening.sessionOpen.value)
        assertFalse(listening.state.value.microphone)
    }

    @Test
    fun aChangeOfTheEarbudMicrophoneRestartsARecordingAndOtherChangesDoNot() = runTest {
        val listening = listeningWithSession()
        log.clear()

        storedOptions.value = ListeningOptions(pauseAfterSilence = true)
        runCurrent()
        storedOptions.value = ListeningOptions(pauseAfterSilence = true, earbudMicrophone = true)
        runCurrent()
        listening.send(Event.Screen(on = false))
        runCurrent()
        // While the microphone is off, a change waits for its next start.
        storedOptions.value = ListeningOptions(pauseAfterSilence = true)
        runCurrent()
        listening.send(Event.Screen(on = true))
        runCurrent()

        assertEquals(
            listOf("microphone off", "microphone on", "microphone off", "microphone on"),
            log.filter { it.startsWith("microphone") },
        )
    }

    /** What MicCapture reports when AudioRecord.read returns ERROR_DEAD_OBJECT, after Android's audio server restarted. */
    private fun readError() = IllegalStateException("The microphone failed with error -6")

    /** A scope whose failures go to [errors] instead of failing the test, for the errors that the controller rethrows. */
    private fun TestScope.catching(errors: MutableList<Throwable>): CoroutineScope = CoroutineScope(
        backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]) +
            CoroutineExceptionHandler { _, e -> errors += e },
    )

    private fun TestScope.listening(
        scope: CoroutineScope = backgroundScope,
        options: Flow<ListeningOptions> = storedOptions,
    ): Listening = Listening(
        scope = scope,
        thread = StandardTestDispatcher(testScheduler),
        sessions = sessions,
        services = services,
        bar = bar,
        report = { what, error -> reports += what to error },
        openSettings = {
            log += "open settings"
            settingsHeld?.await()
            settingsFailure?.let { throw it }
            settingsOpening
        },
        memory = memory,
        conditions = conditions,
        notices = notices,
        exits = exits,
        clock = { now },
        calls = calls,
        callActions = callActions,
        commands = runner,
        turns = turns,
        options = options,
        microphones = { microphone },
        timeline = timeline,
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

    /** Collects the command reports of [listening] from now on. */
    private fun TestScope.collectReports(listening: Listening) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { listening.commandReports.collect { commandReports += it } }
    }

    /** The next command counts down 4 s, as a call does, and counts a call when the countdown runs out. */
    private fun countDown() {
        runner.action = { conversation ->
            if (conversation.countdown(4_000) { "Calling in $it" }) {
                placedCalls++
                CommandResult(Outcome.DONE)
            } else {
                CommandResult(Outcome.CANCELLED)
            }
        }
    }

    private class FakeRunner : CommandRunner {
        val started = mutableListOf<Command>()
        val heard = mutableListOf<String>()
        var password = false

        // What the next command does; by default it ends at once, done and repeatable.
        var action: suspend (CommandConversation) -> CommandResult = { CommandResult(Outcome.DONE, repeatable = true) }

        override suspend fun run(command: Command, heard: String, conversation: CommandConversation): CommandResult {
            started += command
            this.heard += heard
            return action(conversation)
        }

        override fun privateInput() = password
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
        override var voice: VoiceOptions? = null
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

    private class FakeCallMonitor : CallMonitor {
        var starts = 0
        var stops = 0
        var failure: Exception? = null
        var stopFailure: Exception? = null
        private var report: ((CallState) -> Unit)? = null

        // Every report that start() was given, also after stop(): one that is on its way while a session closes may
        // still come.
        val given = mutableListOf<(CallState) -> Unit>()

        override fun start(report: (CallState) -> Unit) {
            starts++
            failure?.let { throw it }
            this.report = report
            given += report
        }

        override fun stop() {
            stops++
            report = null
            stopFailure?.let { throw it }
        }

        /** The phone's call state changes to [state], as Android's listener reports it. */
        fun change(state: CallState) {
            checkNotNull(report) { "Nothing watches the call state" }.invoke(state)
        }
    }

    private class FakeCallControl : CallControl {
        val commands = mutableListOf<Request.Call>()
        var result: String? = null
        var failure: Exception? = null

        override suspend fun perform(command: Request.Call): String? {
            commands += command
            failure?.let { throw it }
            return result
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
            bar = bar,
            report = { what, error -> reports += what to error },
            openSettings = { SettingsOpening.OPENED },
            conditions = conditions,
            notices = notices,
            exits = exits,
            clock = { now },
            calls = calls,
            callActions = callActions,
            commands = runner,
            turns = ScreenTurns({ what, error -> reports += what to error }),
            options = storedOptions,
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
