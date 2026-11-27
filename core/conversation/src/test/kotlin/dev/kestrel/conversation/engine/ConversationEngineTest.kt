package dev.kestrel.conversation.engine

import dev.kestrel.agent.EndReason
import dev.kestrel.agent.Interruption
import dev.kestrel.agent.TaskRequest
import dev.kestrel.agent.goal.Checklist
import dev.kestrel.agent.goal.ChecklistItem
import dev.kestrel.agent.goal.GoalLimits
import dev.kestrel.agent.goal.HistoryQuery
import dev.kestrel.agent.goal.ItemState
import dev.kestrel.agent.port.AccessKind
import dev.kestrel.agent.port.DataField
import dev.kestrel.agent.port.DataLine
import dev.kestrel.agent.port.DataResult
import dev.kestrel.agent.port.HandOverEnd
import dev.kestrel.agent.port.PermissionEnd
import dev.kestrel.agent.testing.ScreenBuilder
import dev.kestrel.agent.testing.ScriptedModel
import dev.kestrel.conversation.ConversationTexts
import dev.kestrel.conversation.channel.ChannelOutput
import dev.kestrel.conversation.channel.DialogueEvent
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.context.SteeringMessage
import dev.kestrel.conversation.goal.GoalRunner
import dev.kestrel.conversation.goal.ScreenTurn
import dev.kestrel.conversation.local.LocalRequests
import dev.kestrel.conversation.machine.ConversationEvent
import dev.kestrel.conversation.machine.ConversationState
import dev.kestrel.conversation.machine.HoldReason
import dev.kestrel.conversation.machine.MachineStep
import dev.kestrel.conversation.machine.QuietClock
import dev.kestrel.conversation.machine.Timer
import dev.kestrel.conversation.model.ChannelAnswer
import dev.kestrel.conversation.model.CommandRecord
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.ConversationSettings
import dev.kestrel.conversation.model.ConversationSnapshot
import dev.kestrel.conversation.model.GoalHold
import dev.kestrel.conversation.model.GoalPhase
import dev.kestrel.conversation.model.GoalState
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.InputIntent
import dev.kestrel.conversation.model.LineKind
import dev.kestrel.conversation.model.OwnerInput
import dev.kestrel.conversation.model.PendingSteer
import dev.kestrel.conversation.model.PhoneSignal
import dev.kestrel.conversation.model.ProcessEnd
import dev.kestrel.conversation.model.RecordLine
import dev.kestrel.conversation.store.ArchivedConversationStore
import dev.kestrel.conversation.store.ConversationStore
import dev.kestrel.conversation.store.HistorySearch
import dev.kestrel.conversation.testing.FakeChannel
import dev.kestrel.conversation.testing.FakeGoalEnvironment
import dev.kestrel.conversation.testing.FakeScreenTurn
import dev.kestrel.conversation.testing.ManualConversationClock
import dev.kestrel.conversation.testing.MemoryArchive
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.Part
import dev.kestrel.safety.HandOverKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.CoroutineContext

/** Whole conversations on the JVM (design 15.1 of sub-project 7), with the scripted model, fake channels and the in-memory archive. */
class ConversationEngineTest {
    private val voice = FakeChannel(InputChannel.VOICE)
    private val text = FakeChannel(InputChannel.TEXT)
    private val archive = MemoryArchive()
    private val scope = TestScope()
    private val store = ArchivedConversationStore(archive, scope.backgroundScope) {}
    private val settings = MutableStateFlow(ConversationSettings())
    private val testMode = MutableStateFlow(false)
    private val kestrelOn = MutableStateFlow(true)
    private val clock = ManualConversationClock({ scope.testScheduler.currentTime })
    private val failures = mutableListOf<Throwable>()
    private var inputs = 0L

    private val sms = DataResult.Ok(listOf(DataLine("sms 1", listOf(DataField("from", "Mila"), DataField("body", "Ambar at 8")))))
    private val listSms = ScriptedModel.call("list_sms", null, null, null, "limit" to 1)
    private val fail = ScriptedModel.call("fail", null, null, null, "reason" to "Stop.")

    private fun finish(step: Int) = ScriptedModel.call("finish", null, null, null, "summary" to "Mila wrote: Ambar at 8.", "evidence" to listOf(mapOf("step" to step)))

    private fun say(words: String) = ScriptedModel.call("say", null, null, null, "text" to words)

    private fun environment(vararg replies: JsonObject) = FakeGoalEnvironment(replies.toList()).apply { data.answer("list_sms", sms) }

    private fun TestScope.engine(
        environment: FakeGoalEnvironment,
        store: ConversationStore = this@ConversationEngineTest.store,
        onStep: (ConversationEvent, ConversationState, MachineStep) -> Unit = { _, _, _ -> },
        report: (String, Throwable) -> Unit = { _, error -> throw error },
        screen: ScreenTurn = FakeScreenTurn { false },
        timerMillis: (Timer, Long) -> Long = { _, millis -> millis },
        engineScope: CoroutineScope = backgroundScope,
    ): ConversationEngine {
        val search = HistorySearch(store, { settings.value.keptDays }, clock)
        val goals = GoalRunner(environment, screen, search, clock) { _, error -> throw error }
        val local = LocalRequests(environment.memory, store, search, goals::saveRoutine, clock) { _, error -> throw error }
        val engine = ConversationEngine(
            engineScope, store, goals, local, mapOf(InputChannel.VOICE to voice, InputChannel.TEXT to text), { PermissionEnd.GRANTED },
            settings, testMode, kestrelOn, clock, report, onStep = onStep, timerMillis = timerMillis,
        )
        // The phone is unlocked with no call, as plan 7D's PhoneSignals says when it starts. The engine handles the signal
        // right after restore(), so the owner is there: a restored goal asks "Continue the goal?" and final replies are given.
        engine.signal(PhoneSignal.UNLOCKED)
        return engine
    }

    private fun input(channel: InputChannel, intent: InputIntent) = OwnerInput(++inputs, channel, intent, 0)

    private fun typed(words: String) = input(InputChannel.TEXT, InputIntent.Command(words))

    private fun spoken(words: String) = input(InputChannel.VOICE, InputIntent.Command(words))

    private fun ChatRequest.text(): String = (listOf(context.orEmpty()) + messages.mapNotNull { message ->
        when (message) {
            is ChatMessage.ToolResult -> message.text
            is ChatMessage.User -> message.parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text }
            is ChatMessage.Assistant -> null
        }
    }).joinToString("\n")

    @Test
    fun aTypedCommandRunsAGoalAndItsReplyEndsTheCommand() = scope.runTest {
        val engine = engine(environment(listSms, finish(1)))
        assertEquals(RestoreResult.NOTHING, engine.restore())

        engine.submit(typed("Read my latest SMS"))
        runCurrent()

        val view = engine.view.value
        val record = view.records.single()
        assertEquals("Read my latest SMS", record.request)
        assertEquals(EndReason.FINISHED, record.end)
        assertEquals(1, record.data.size)
        val outcome = engine.outcome(1)!!
        assertEquals(ChannelOutput.Report(1, FakeGoalEnvironment.sentenceOf(outcome.report)), text.shown.last())
        assertTrue(voice.shown.isEmpty())
        assertNull(view.goal)
        assertTrue(view.quiet is QuietClock.Running)
    }

    @Test
    fun aSecondCommandUsesTheFirstOnesRecord() = scope.runTest {
        val environment = environment(listSms, finish(1), fail)
        val engine = engine(environment)
        engine.restore()

        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        engine.submit(typed("Send it to me in Saved Messages"))
        runCurrent()

        val second = environment.model.requests[2].context.orEmpty()
        assertTrue(second.contains(TaskRequest.CONVERSATION_HEADER))
        assertTrue(second.contains("The owner said: \"Read my latest SMS\""))
        assertEquals(2, engine.view.value.records.size)
        assertEquals(engine.view.value.id, store.open()!!.id)
    }

    @Test
    fun theQuietTimeClosesTheConversationIntoTheStore() = scope.runTest {
        val engine = engine(environment(listSms, finish(1)))
        engine.restore()
        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        val id = engine.view.value.id!!

        advanceTimeBy(59_000)
        runCurrent()
        assertEquals(id, engine.view.value.id)

        advanceTimeBy(2_000)
        runCurrent()
        assertNull(engine.view.value.id)
        assertEquals(listOf(id), store.list().map { it.id })
        assertNull(store.open())
    }

    @Test
    fun theRestoreReadsWhetherKestrelIsOnBeforeItRestores() = scope.runTest {
        kestrelOn.value = false
        var restoredOn: Boolean? = null
        val engine = engine(environment(), onStep = { event, before, _ -> if (event is ConversationEvent.Restored) restoredOn = before.voiceOn })

        engine.restore()

        assertEquals(false, restoredOn)
    }

    @Test
    fun aRestartWithinTheQuietTimeKeepsTheConversationOpenForTheRest() = scope.runTest {
        val first = engine(environment(listSms, finish(1)))
        first.restore()
        first.submit(typed("Read my latest SMS"))
        runCurrent()
        val id = first.view.value.id
        advanceTimeBy(20_000)

        val second = engine(environment(), ArchivedConversationStore(archive, backgroundScope) {})
        assertEquals(RestoreResult.OPEN, second.restore())
        runCurrent()
        assertEquals(id, second.view.value.id)
        assertEquals(1, second.view.value.records.size)

        advanceTimeBy(41_000)
        runCurrent()
        assertNull(second.view.value.id)
    }

    @Test
    fun aGoalCutByARestartIsHeldAndContinuesFromItsChecklistAfterYes() = scope.runTest {
        val now = ManualConversationClock.BASE
        val checklist = Checklist(listOf(ChecklistItem("c1", "Open Gmail", ItemState.DONE, step = 1), ChecklistItem("c2", "Archive the newsletters", ItemState.TODO)))
        val record = CommandRecord(1, now - 60_000, InputChannel.TEXT, "Archive my newsletters", ledger = listOf("opened Gmail"), checklist = checklist)
        store.save(ConversationSnapshot(
            ConversationId("0123456789abcdef0123456789abcdef"), now - 60_000, now - 30_000, open = true, testMode = false,
            records = listOf(record), goal = GoalState(1, GoalPhase.ACTING, now - 30_000), steering = listOf(PendingSteer("only the ones from 2025", InputChannel.TEXT)),
        ))
        val environment = environment(fail)
        val engine = engine(environment, ArchivedConversationStore(archive, backgroundScope) {})

        assertEquals(RestoreResult.GOAL_HELD, engine.restore())
        runCurrent()
        assertEquals(GoalPhase.INTERRUPTED, engine.view.value.goal)
        assertEquals(QuestionKind.CONTINUE_GOAL, voice.asked.single().kind)
        assertEquals(QuestionKind.CONTINUE_GOAL, text.asked.single().kind)

        engine.submit(input(InputChannel.VOICE, InputIntent.Answer(ChannelAnswer.Yes)))
        runCurrent()

        val resumed = environment.model.requests.first().text()
        assertTrue(resumed.contains("You are continuing this goal after a restart of Kestrel."))
        assertTrue(resumed.contains("c2 [todo] \"Archive the newsletters\""))
        assertTrue(resumed.contains("What Kestrel did so far, in its own notes: \"opened Gmail\"\n"))
        assertTrue(resumed.contains("The owner added (typed) before the first step: \"only the ones from 2025\""))
        assertNull(engine.view.value.goal)
    }

    // Plan 7B's decision 10 (plan 7F): the engine's channels take each output as the engine emits it, also while an earlier
    // one is still shown, so that a channel can drop the outputs that wait behind one that a stop ended.
    @Test
    fun theChannelsTakeEachOutputAsTheEngineEmitsIt() = scope.runTest {
        val showing = CompletableDeferred<Unit>()
        text.onShow = { showing.await() }
        val engine = engine(environment())
        engine.restore()

        engine.submit(input(InputChannel.TEXT, InputIntent.Continue))
        runCurrent()
        engine.submit(input(InputChannel.TEXT, InputIntent.Continue))
        runCurrent()
        assertEquals(2, text.takes)
        assertEquals(1, text.shown.size)

        showing.complete(Unit)
        runCurrent()
        assertEquals(2, text.shown.size)
    }

    // Re-review round 2, R2-1: each round's screen turn names the owner's input that lets it act: the command that started
    // the goal, and the Continue that resumed it after a touch, so that only an instant command that he started before that
    // input is cancelled.
    @Test
    fun aRoundsScreenTurnNamesTheInputThatStartedOrResumedIt() = scope.runTest {
        val speaking = CompletableDeferred<Unit>()
        text.onShow = { if (it == ChannelOutput.Said("On it.")) speaking.await() }
        val afters = mutableListOf<Long?>()
        val engine = engine(environment(say("On it."), listSms, finish(1)), screen = FakeScreenTurn { after -> afters += after; false })
        engine.restore()
        val command = typed("Read my latest SMS")
        engine.submit(command)
        runCurrent()
        assertEquals(command.id, afters.first())

        engine.touched()
        runCurrent()
        afters.clear()
        val continued = input(InputChannel.TEXT, InputIntent.Continue)
        engine.submit(continued)
        runCurrent()

        assertEquals(continued.id, afters.first())
        assertTrue(afters.drop(1).all { it == null })
    }

    // A goal that waited behind the stop of the one before it starts for its own input, which is older than an instant
    // command that the owner started meanwhile, so that command is not cancelled.
    @Test
    fun aGoalFromTheQueueNamesItsOwnInput() = scope.runTest {
        val speaking = CompletableDeferred<Unit>()
        text.onShow = { if (it == ChannelOutput.Said("On it.")) speaking.await() }
        val afters = mutableListOf<Long?>()
        val engine = engine(environment(say("On it."), listSms, finish(1)), screen = FakeScreenTurn { after -> afters += after; false })
        engine.restore()
        engine.submit(typed("Read my latest SMS"))
        runCurrent()

        engine.submit(input(InputChannel.TEXT, InputIntent.Stop))
        val queued = typed("Read it again")
        engine.submit(queued)
        runCurrent()

        assertEquals(listOf(1L, queued.id), afters.filterNotNull())
        assertEquals(2, engine.view.value.records.size)
    }

    // Plan 7F: a yes on the card of "Continue the goal?" is the owner's act of the moment of his tap, whose number `:app`
    // takes as the tap reaches Kestrel. An input that he gives after the tap, such as the start of an instant command,
    // before the engine's loop takes the answer, is newer than the round's act, so the round never cancels that command.
    @Test
    fun aYesOnTheCardOfContinueTheGoalNamesTheTapsOwnNumber() = scope.runTest {
        val now = ManualConversationClock.BASE
        val record = CommandRecord(1, now - 60_000, InputChannel.TEXT, "Archive my newsletters", ledger = listOf("opened Gmail"))
        store.save(ConversationSnapshot(
            ConversationId("0123456789abcdef0123456789abcdef"), now - 60_000, now - 30_000, open = true, testMode = false,
            records = listOf(record), goal = GoalState(1, GoalPhase.ACTING, now - 30_000),
        ))
        text.answers += ChannelAnswer.Yes
        // The owner's tap takes number 1, and an input that he gives right after it, before the engine's loop takes the
        // answer, such as an instant command's start, takes number 2.
        text.taps = { (++inputs).also { ++inputs } }
        val afters = mutableListOf<Long?>()
        val engine = engine(environment(fail), ArchivedConversationStore(archive, backgroundScope) {}, screen = FakeScreenTurn { after -> afters += after; false })

        assertEquals(RestoreResult.GOAL_HELD, engine.restore())
        runCurrent()

        // The round names the tap's own number, older than the input after it, which it so never lets go; the engine took no
        // number of its own (it would have been 3, newer than that input).
        assertEquals(1L, afters.first())
        assertEquals(2L, inputs)
    }

    @Test
    fun aRestoreAfterTheOwnersStopEndsTheGoalWithItsReportAndAsksNothing() = scope.runTest {
        val now = ManualConversationClock.BASE
        val record = CommandRecord(1, now - 60_000, InputChannel.TEXT, "Archive my newsletters", ledger = listOf("opened Gmail"))
        store.save(ConversationSnapshot(
            ConversationId("0123456789abcdef0123456789abcdef"), now - 60_000, now - 30_000, open = true, testMode = false,
            records = listOf(record), goal = GoalState(1, GoalPhase.ACTING, now - 30_000),
        ))
        val environment = environment()
        val engine = engine(environment, ArchivedConversationStore(archive, backgroundScope) {})

        assertEquals(RestoreResult.GOAL_ENDED, engine.restore(ProcessEnd.OwnerStopped(now - 10_000)))
        runCurrent()

        // Plan 7E: no round runs, nothing asks "Continue the goal?", and the record ends as "stop" ends it.
        assertNull(engine.view.value.goal)
        assertTrue(voice.asked.isEmpty() && text.asked.isEmpty())
        assertTrue(environment.starts.isEmpty())
        assertEquals(EndReason.CANCELLED, engine.view.value.records.single().end)
    }

    @Test
    fun androidsRefusalOfTheGoalServiceHoldsTheGoalAndContinueResumesIt() = scope.runTest {
        val speaking = CompletableDeferred<Unit>()
        text.onShow = { if (it == ChannelOutput.Said("On it.")) speaking.await() }
        val environment = environment(say("On it."), listSms, finish(1))
        val engine = engine(environment)
        engine.restore()
        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        assertEquals(GoalPhase.ACTING, engine.view.value.goal)

        engine.backgroundRefused(checkNotNull(engine.view.value.goalCommand))
        runCurrent()

        // Plan 7E; design 10.6 of sub-project 7: the goal is held as interrupted, and the view names why.
        assertEquals(GoalPhase.INTERRUPTED, engine.view.value.goal)
        assertEquals(GoalHold.BACKGROUND_REFUSED, engine.view.value.hold)
        assertEquals(1, engine.view.value.goalRecord)
        assertEquals(Interruption.BACKGROUND_REFUSED, engine.outcome(1)!!.report.interruption)
        assertTrue(text.texts().last().endsWith(ConversationTexts.held(GoalHold.BACKGROUND_REFUSED)))

        engine.submit(input(InputChannel.TEXT, InputIntent.Continue))
        runCurrent()
        assertNull(engine.view.value.goal)
        assertNull(engine.view.value.hold)
        assertEquals(EndReason.FINISHED, engine.view.value.records.single().end)
    }

    // Plan 7E: a refusal is the only input that interrupts a handed-over goal. The round that waits in the hand-over is
    // cancelled, the hand-over closes, and the goal is held as refused, not working; Continue resumes it under a new number.
    @Test
    fun androidsRefusalDuringAHandOverClosesItAndHoldsTheGoalUntilContinue() = scope.runTest {
        val environment = FakeGoalEnvironment(listOf(listSms, listSms, finish(1))).apply {
            data.answer("list_sms", DataResult.NeedsAccess(AccessKind.NOTIFICATIONS), sms)
        }
        val dialogue = mutableListOf<DialogueEvent>()
        var machine: ConversationState? = null
        val engine = engine(environment, onStep = { event, _, step ->
            if (event is ConversationEvent.Dialogue) dialogue += event.event
            machine = step.state
        })
        engine.restore()
        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        assertEquals(GoalPhase.HANDED_OVER, engine.view.value.goal)
        assertEquals(HandOverKind.ACCESS, engine.owner.handOverKind)
        val handedOver = dialogue.filterIsInstance<DialogueEvent.HandedOver>().single().number
        val refused = checkNotNull(engine.view.value.goalCommand)

        engine.backgroundRefused(refused)
        runCurrent()

        assertEquals(GoalPhase.INTERRUPTED, engine.view.value.goal)
        assertEquals(GoalHold.BACKGROUND_REFUSED, engine.view.value.hold)
        assertEquals(Interruption.BACKGROUND_REFUSED, engine.outcome(refused)!!.report.interruption)
        assertNull(engine.owner.handOverKind)
        assertEquals(DialogueEvent.HandOverDone(handedOver), dialogue.last { it is DialogueEvent.HandOverDone })
        assertNull(machine!!.goal!!.handOver)
        assertEquals(1, text.handOvers.size)

        // The resumed round waits before its last model call, so that a late refusal of the old number meets a working goal.
        val waiting = CompletableDeferred<Unit>()
        environment.beforeCall = { call -> if (call == 3) waiting.await() }
        engine.submit(input(InputChannel.TEXT, InputIntent.Continue))
        runCurrent()
        assertEquals(GoalPhase.ACTING, engine.view.value.goal)
        val resumed = checkNotNull(engine.view.value.goalCommand)
        assertNotEquals(refused, resumed)

        engine.backgroundRefused(refused)
        runCurrent()
        assertEquals(GoalPhase.ACTING, engine.view.value.goal)
        assertNull(engine.view.value.hold)
        assertEquals(resumed, engine.view.value.goalCommand)

        waiting.complete(Unit)
        runCurrent()
        assertNull(engine.view.value.goal)
        assertEquals(EndReason.FINISHED, engine.view.value.records.single().end)
    }

    // Plan 7E: a tap on the goal notification can start Kestrel's process; the receiver waits until the conversation is
    // restored, and then reads the restored goal, which the view names by its conversation and its record.
    @Test
    fun awaitRestoredReturnsOnceTheRestoreHasRunWithTheRestoredGoalInTheView() = scope.runTest {
        val now = ManualConversationClock.BASE
        val id = ConversationId("0123456789abcdef0123456789abcdef")
        val record = CommandRecord(1, now - 60_000, InputChannel.TEXT, "Archive my newsletters")
        store.save(ConversationSnapshot(id, now - 60_000, now - 30_000, open = true, testMode = false, records = listOf(record), goal = GoalState(1, GoalPhase.ACTING, now - 30_000)))
        val engine = engine(environment(), ArchivedConversationStore(archive, backgroundScope) {})
        var seen: ConversationView? = null
        launch {
            engine.awaitRestored()
            seen = engine.view.value
        }
        runCurrent()
        assertNull(seen)

        assertEquals(RestoreResult.GOAL_HELD, engine.restore())
        runCurrent()

        assertEquals(id, seen?.id)
        assertEquals(GoalPhase.INTERRUPTED, seen?.goal)
        assertEquals(1, seen?.goalRecord)
    }

    @Test
    fun aTouchInterruptsTheGoalAndContinueResumesItInANewRoundThatCountsOnItsSteps() = scope.runTest {
        val speaking = CompletableDeferred<Unit>()
        text.onShow = { if (it == ChannelOutput.Said("On it.")) speaking.await() }
        val environment = environment(say("On it."), listSms, finish(1))
        val engine = engine(environment)
        val steps = mutableListOf<Int?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { engine.view.collect { steps += it.step } }
        engine.restore()
        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        assertEquals(GoalPhase.ACTING, engine.view.value.goal)

        engine.touched()
        runCurrent()
        assertEquals(GoalPhase.INTERRUPTED, engine.view.value.goal)
        assertTrue(text.texts().last().endsWith(ConversationTexts.GO_ON))

        engine.submit(input(InputChannel.TEXT, InputIntent.Continue))
        runCurrent()
        assertNull(engine.view.value.goal)
        assertEquals(EndReason.FINISHED, engine.view.value.records.single().end)
        assertTrue(environment.model.requests[1].text().contains("You are continuing this goal after an interruption."))
        // The first round took one step, so the second round's steps show as 2 and 3; no step shows without a working goal.
        assertEquals(listOf(1, 2, 3), steps.filterNotNull().distinct())
        assertNull(engine.view.value.step)
        // The record names the data of the second round's first step as the goal's step 2, as the bar counted it.
        assertEquals(listOf(2), engine.view.value.records.single().data.map { it.step })
    }

    @Test
    fun aSteerThatTheRunTookBeforeAnInterruptionReachesTheResumedRound() = scope.runTest {
        val environment = environment(say("On it."), listSms, listSms, finish(1))
        val engine = engine(environment)
        // The steer comes after the first reply; the step's traces wait for the engine, which takes the steer first, so the run
        // takes it at its next turn; and a touch ends the round right after that turn's reply.
        environment.afterCall = { calls ->
            if (calls == 1) engine.submit(typed("Also check Ana's"))
            if (calls == 2) engine.touched()
        }
        engine.restore()

        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        assertEquals(GoalPhase.INTERRUPTED, engine.view.value.goal)
        assertTrue(environment.model.requests[1].text().contains("\"Also check Ana's\""))

        engine.submit(input(InputChannel.TEXT, InputIntent.Continue))
        runCurrent()
        assertTrue(environment.model.requests[2].text().contains("The owner added (typed) in an earlier round: \"Also check Ana's\""))
        assertEquals(EndReason.FINISHED, engine.view.value.records.single().end)
    }

    @Test
    fun aLimitWithoutAnAnswerPausesTheGoalAndACommandResumesItAsSteering() = scope.runTest {
        settings.value = ConversationSettings(limits = GoalLimits(workMinutes = 5))
        text.answers += ChannelAnswer.Silence
        val environment = environment(listSms, fail)
        // The first step takes the round's 5 minutes of working time.
        environment.afterCall = { calls -> if (calls == 1) environment.clock.elapsed += 6 * 60_000L }
        val engine = engine(environment)
        engine.restore()

        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        assertEquals(GoalPhase.PAUSED_AT_LIMIT, engine.view.value.goal)
        assertEquals(QuestionKind.LIMIT, text.asked.single().kind)

        engine.submit(typed("Also check Ana's"))
        runCurrent()
        assertNull(engine.view.value.goal)
        val resumed = environment.model.requests[1].text()
        assertTrue(resumed.contains("You are continuing this goal after a pause at a limit."))
        assertTrue(resumed.contains("The owner added (typed) before the first step: \"Also check Ana's\""))
    }

    @Test
    fun continuingAPastConversationMakesItTheCurrentOneWithItsRecords() = scope.runTest {
        val engine = engine(environment(listSms, finish(1)))
        engine.restore()
        engine.submit(spoken("Read my latest SMS"))
        runCurrent()
        val past = engine.view.value.id!!
        engine.endQuietTime()
        runCurrent()
        assertNull(engine.view.value.id)

        engine.submit(input(InputChannel.VOICE, InputIntent.ContinuePast("latest SMS")))
        runCurrent()
        assertEquals(QuestionKind.SWITCH, voice.asked.last().kind)
        engine.submit(input(InputChannel.VOICE, InputIntent.Answer(ChannelAnswer.Yes)))
        runCurrent()

        val view = engine.view.value
        assertEquals(past, view.id)
        assertTrue(view.continued)
        assertEquals(1, view.records.size)
        assertEquals(ChannelOutput.Report(view.command, "We're back in the conversation from Friday, 20 November."), voice.shown.last())
        // The hooks read the switch's final reply as its outcome (design 14.4).
        assertEquals("We're back in the conversation from Friday, 20 November.", engine.outcome(view.command)!!.answer)
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun continuingFromAnOpenConversationClosesItWithItsRecordsAndNoGoal() = scope.runTest {
        val engine = engine(environment(listSms, finish(1), listSms, finish(1)))
        engine.restore()
        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        val past = engine.view.value.id!!
        engine.endQuietTime()
        runCurrent()
        engine.submit(typed("Read it again"))
        runCurrent()
        val current = engine.view.value.id!!

        engine.submit(input(InputChannel.TEXT, InputIntent.ContinueConversation(past)))
        runCurrent()

        assertEquals(past, engine.view.value.id)
        assertEquals(past, store.open()!!.id)
        val closed = store.get(current)!!
        assertFalse(closed.open)
        assertNull(closed.goal)
        assertEquals(listOf("Read it again"), closed.records.map { it.request })
        assertEquals(EndReason.FINISHED, closed.records.single().end)
    }

    @Test
    fun deleteHistoryLeavesNoConversationBehind() = scope.runTest {
        text.answers += ChannelAnswer.Yes
        val engine = engine(environment(listSms, finish(1)))
        engine.restore()
        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        assertTrue(archive.rows.isNotEmpty())

        engine.submit(input(InputChannel.TEXT, InputIntent.DeleteHistory))
        runCurrent()

        assertNull(engine.view.value.id)
        assertTrue(archive.rows.isEmpty())
        assertTrue(archive.keyErased)
        assertEquals(ChannelOutput.Report(2, ConversationTexts.HISTORY_DELETED), text.shown.last())
    }

    @Test
    fun theSettingsPagesDeleteHistoryAsksNothingMoreAndLeavesAnOpenGoalAlone() = scope.runTest {
        val engine = engine(environment(ScriptedModel.call("ask_user", null, null, null, "question" to "Which one?"), fail))
        engine.restore()
        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        assertEquals(GoalPhase.ASKING, engine.view.value.goal)

        assertEquals(HistoryDeletion.REFUSED, engine.deleteHistory())
        assertTrue(archive.rows.isNotEmpty())

        engine.submit(input(InputChannel.TEXT, InputIntent.Stop))
        runCurrent()
        assertNull(engine.view.value.goal)
        val asked = text.asked.size + voice.asked.size
        val shown = text.shown.size + voice.shown.size

        assertEquals(HistoryDeletion.DELETED, engine.deleteHistory())
        runCurrent()

        assertNull(engine.view.value.id)
        assertTrue(archive.rows.isEmpty())
        assertTrue(archive.keyErased)
        // The page asked its own question, and shows the result itself.
        assertEquals(asked, text.asked.size + voice.asked.size)
        assertEquals(shown, text.shown.size + voice.shown.size)
    }

    // The engine's writer deletes after every write before the request: a save that waits for the store is deleted too,
    // and a command after the request opens a conversation that stays.
    @Test
    fun theSettingsPagesDeleteHistoryComesAfterTheWritesBeforeItAndKeepsTheCommandsAfterIt() = scope.runTest {
        val gated = GatedStore(store)
        val engine = engine(environment(listSms, finish(1), listSms, finish(1)), gated)
        engine.restore()
        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        val first = engine.view.value.id
        assertNotNull(first)
        val gate = CompletableDeferred<Unit>()
        gated.gate = gate

        // The close's save waits for the store, and the deletion waits behind it.
        engine.submit(input(InputChannel.TEXT, InputIntent.NewConversation))
        runCurrent()
        var deletion: HistoryDeletion? = null
        launch { deletion = engine.deleteHistory() }
        runCurrent()
        engine.submit(typed("Read my newest SMS"))
        runCurrent()
        assertNull(deletion)

        gate.complete(Unit)
        runCurrent()

        assertEquals(HistoryDeletion.DELETED, deletion)
        assertTrue(archive.keyErased)
        val kept = store.open()!!
        assertNotEquals(first, kept.id)
        assertEquals(listOf("Read my newest SMS"), kept.records.map { it.request })
        assertEquals(listOf(kept.id.value), archive.rows.keys.toList())
    }

    // A conversation that a command after the request opens, and that closes while the store still deletes, waits for the
    // deletion with its close, and stays.
    @Test
    fun aConversationThatOpensAndClosesWhileTheHistoryIsDeletedStays() = scope.runTest {
        val gated = GatedStore(store)
        val engine = engine(environment(listSms, finish(1), listSms, finish(1)), gated)
        engine.restore()
        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        val deleting = CompletableDeferred<Unit>()
        gated.deleting = deleting
        var deletion: HistoryDeletion? = null
        launch { deletion = engine.deleteHistory() }
        runCurrent()

        engine.submit(typed("Read my newest SMS"))
        runCurrent()
        val newer = engine.view.value.id!!
        engine.submit(input(InputChannel.TEXT, InputIntent.NewConversation))
        runCurrent()
        assertNull(deletion)

        deleting.complete(Unit)
        runCurrent()

        assertEquals(HistoryDeletion.DELETED, deletion)
        val kept = store.get(newer)!!
        assertFalse(kept.open)
        assertEquals(listOf("Read my newest SMS"), kept.records.map { it.request })
    }

    // A switch that "continue the conversation about …" asked for just before the request is that request's end, after
    // the request itself has ended: the page's request is refused, and the switch ends with the conversation that it opened.
    @Test
    fun theSettingsPagesDeleteHistoryIsRefusedWhileASwitchRuns() = scope.runTest {
        val gated = GatedStore(store)
        val engine = engine(environment(listSms, finish(1)), gated)
        engine.restore()
        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        val past = engine.view.value.id!!
        engine.endQuietTime()
        runCurrent()
        val reopening = CompletableDeferred<Unit>()
        gated.reopenGate = reopening

        engine.submit(input(InputChannel.VOICE, InputIntent.ContinuePast("latest SMS")))
        runCurrent()
        engine.submit(input(InputChannel.VOICE, InputIntent.Answer(ChannelAnswer.Yes)))
        runCurrent()
        assertEquals(HistoryDeletion.REFUSED, engine.deleteHistory())

        reopening.complete(Unit)
        runCurrent()
        assertEquals(past, engine.view.value.id)
        assertEquals(past, store.open()!!.id)
        assertFalse(archive.keyErased)
    }

    @Test
    fun theQuietClockWaitsUntilTheSpokenReplyHasEnded() = scope.runTest {
        val speaking = CompletableDeferred<Unit>()
        voice.onShow = { if (it is ChannelOutput.Report) speaking.await() }
        val engine = engine(environment(listSms, finish(1)))
        engine.restore()
        engine.submit(spoken("Read my latest SMS"))
        runCurrent()
        assertEquals(QuietClock.Held(HoldReason.REPLYING), engine.view.value.quiet)

        advanceTimeBy(120_000)
        runCurrent()
        assertNotNull(engine.view.value.id)

        speaking.complete(Unit)
        runCurrent()
        assertTrue(engine.view.value.quiet is QuietClock.Running)
    }

    @Test
    fun aRecordGrowsAndIsSavedWhileItsGoalWorks() = scope.runTest {
        var saved: ConversationSnapshot? = null
        text.onShow = { if (it == ChannelOutput.Said("Found it.")) saved = ArchivedConversationStore(archive, backgroundScope) {}.open() }
        val engine = engine(environment(listSms, say("Found it."), finish(1)))
        engine.restore()

        engine.submit(typed("Read my latest SMS"))
        runCurrent()

        val record = saved!!.records.single()
        assertEquals(1, record.data.size)
        assertNull(record.end)
        assertEquals(GoalPhase.ACTING, saved!!.goal!!.phase)
        assertEquals(EndReason.FINISHED, engine.view.value.records.single().end)
    }

    // The conversation screen's "Stop and start new" (design 6.8): the step that ends the goal also closes the conversation,
    // which is stored with the goal's end and Kestrel's final words.
    @Test
    fun stopAndStartNewStoresTheStoppedGoalWithItsEndAndAnswer() = scope.runTest {
        text.onShow = { if (it == ChannelOutput.Said("On it.")) awaitCancellation() }
        val engine = engine(environment(say("On it."), listSms, finish(1)))
        engine.restore()
        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        val stopped = engine.view.value.id!!

        engine.submit(input(InputChannel.TEXT, InputIntent.Stop))
        engine.submit(input(InputChannel.TEXT, InputIntent.NewConversation))
        runCurrent()

        assertNull(engine.view.value.id)
        val record = store.get(stopped)!!.records.single()
        assertEquals(EndReason.CANCELLED, record.end)
        assertEquals(engine.outcome(1)!!.answer, record.answer)
        assertNotNull(record.answer)
    }

    // Design 12.4: a change of test mode during a goal stops it and then closes the conversation with the goal's end.
    @Test
    fun aTestModeChangeDuringAGoalStopsItAndStoresItsEndBeforeTheClose() = scope.runTest {
        text.onShow = { if (it == ChannelOutput.Said("On it.")) awaitCancellation() }
        val engine = engine(environment(say("On it."), listSms, finish(1)))
        engine.restore()
        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        val id = engine.view.value.id!!

        testMode.value = true
        runCurrent()

        assertNull(engine.view.value.id)
        assertTrue(engine.view.value.testMode)
        val record = store.get(id)!!.records.single()
        assertEquals(EndReason.CANCELLED, record.end)
        assertEquals(ChannelOutput.Report(1, record.answer!!), text.shown.last())
        assertNull(store.open())
    }

    // The engine's loop takes the "stop" before the request's job first runs (the test's dispatcher runs nothing until
    // runCurrent): the request still ends, as cancelled, and the conversation goes on.
    @Test
    fun aStopBeforeALocalRequestFirstRunsStillEndsIt() = scope.runTest {
        val engine = engine(environment(listSms, finish(1), listSms, finish(1)))
        engine.restore()
        engine.submit(typed("Read my latest SMS"))
        runCurrent()

        engine.submit(input(InputChannel.TEXT, InputIntent.DeleteHistory))
        engine.submit(input(InputChannel.TEXT, InputIntent.Stop))
        runCurrent()

        assertTrue(engine.view.value.quiet is QuietClock.Running)
        assertTrue(text.asked.none { it.kind == QuestionKind.DELETE_HISTORY })
        assertEquals(EndReason.CANCELLED, engine.outcome(2)!!.report.end)
        assertTrue(archive.rows.isNotEmpty())

        engine.submit(typed("Read it again"))
        runCurrent()
        assertEquals(listOf(EndReason.FINISHED, EndReason.FINISHED), engine.view.value.records.map { it.end })
    }

    // "Stop. Kestrel, …" while Kestrel's own request asks (review of plan 7D's Task 3, M1): the view shows the request
    // while it runs, so that the voice stops it first; the stop withdraws its question, and the command after it runs as
    // the conversation's next command once the request has ended.
    @Test
    fun aStopAndACommandWhileKestrelsOwnRequestAsksStopTheRequestAndRunTheCommand() = scope.runTest {
        val engine = engine(environment(listSms, finish(1), listSms, finish(1)))
        engine.restore()
        engine.submit(typed("Read my latest SMS"))
        runCurrent()

        engine.submit(input(InputChannel.TEXT, InputIntent.DeleteHistory))
        runCurrent()
        assertTrue(engine.view.value.ownRequest)
        assertEquals(QuestionKind.DELETE_HISTORY, engine.view.value.asking)

        engine.submit(input(InputChannel.TEXT, InputIntent.Stop))
        engine.submit(typed("Read it again"))
        runCurrent()

        assertFalse(engine.view.value.ownRequest)
        assertEquals(EndReason.CANCELLED, engine.outcome(2)!!.report.end)
        assertTrue(archive.rows.isNotEmpty())
        assertEquals(listOf("Read my latest SMS", "Read it again"), engine.view.value.records.map { it.request })
        assertEquals(listOf(EndReason.FINISHED, EndReason.FINISHED), engine.view.value.records.map { it.end })
    }

    // A message that comes after the round gave its final words, here while its routine offer's report is shown, is named
    // as too late at once; the record keeps it so, and no "Got it." says that a round will take it.
    @Test
    fun aSteerAfterTheGoalsFinalWordsIsNamedTooLateAtOnce() = scope.runTest {
        val start = ScreenBuilder.screen("dev.kestrel.testapp", "Kestrel fixtures") { button("Open", 300) }
        val opened = ScreenBuilder.screen("dev.kestrel.testapp", "Kestrel fixtures") { text("Opened", 300) }
        val tap = ScriptedModel.call("tap", "Open", null, null, "reason" to "open it", "category" to "reading")
        val done = ScriptedModel.call(
            "finish", null, null, null,
            "summary" to "Opened.", "evidence" to listOf(mapOf("target" to mapOf("label" to "Opened"))), "signature" to "fixtures.open.button",
        )
        val environment = FakeGoalEnvironment(listOf(tap, done), first = start).apply {
            offersRoutines = true
            screen.onTap("Open", opened)
            memory.signatures["fixtures.open.button"] = 1
        }
        // The owner says no to the routine offer.
        text.answers += ChannelAnswer.No
        val engine = engine(environment)
        var steered = false
        text.onShow = {
            if (it is ChannelOutput.Said && it.text.startsWith("Ended finished") && !steered) {
                steered = true
                engine.submit(typed("Now send it to Ana"))
            }
        }
        engine.restore()

        engine.submit(typed("Open it"))
        runCurrent()

        assertTrue(steered)
        assertTrue(text.shown.contains(ChannelOutput.Said(ConversationTexts.cameTooLate(SteeringMessage.of("Now send it to Ana", InputChannel.TEXT)))))
        assertFalse(text.shown.contains(ChannelOutput.Shown(ConversationTexts.GOT_IT)))
        val record = engine.view.value.records.single()
        assertEquals(RecordLine(LineKind.OWNER_TOO_LATE, "Now send it to Ana", InputChannel.TEXT), record.lines.single { it.kind.owners })
        assertEquals(EndReason.FINISHED, record.end)
        assertNull(engine.view.value.goal)
        assertEquals(1, environment.starts.size)
        assertTrue(store.open()!!.steering.isEmpty())
    }

    // The quiet time is a deadline on the clock that counts the phone's sleep, which no coroutine's delay counts: a command
    // after the night finds the old conversation closed.
    @Test
    fun theQuietTimeEndsByTheClockThatCountsSleep() = scope.runTest {
        val engine = engine(environment(listSms, finish(1), listSms, finish(1)))
        engine.restore()
        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        val first = engine.view.value.id!!

        clock.sleep(8 * 60 * 60_000L)
        engine.submit(typed("Read my latest SMS"))
        runCurrent()

        assertNotEquals(first, engine.view.value.id)
        assertEquals(1, engine.view.value.records.size)
        assertEquals(listOf(first), store.list().map { it.id })
    }

    // A held goal's 30 minutes are a deadline on the same clock: a signal after them ends the goal with its partial report.
    @Test
    fun aHeldGoalEndsAfterItsThirtyMinutesByTheClockThatCountsSleep() = scope.runTest {
        text.onShow = { if (it == ChannelOutput.Said("On it.")) awaitCancellation() }
        val engine = engine(environment(say("On it."), listSms, finish(1)))
        engine.restore()
        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        engine.touched()
        runCurrent()
        assertEquals(GoalPhase.INTERRUPTED, engine.view.value.goal)

        clock.sleep(31 * 60_000L)
        engine.signal(PhoneSignal.UNLOCKED)
        runCurrent()

        assertNull(engine.view.value.goal)
        val record = engine.view.value.records.single()
        assertEquals(EndReason.PAUSED_TOO_LONG, record.end)
        assertEquals(ChannelOutput.Report(1, record.answer!!), text.shown.last())
    }

    @Test
    fun stopEndsAnActingGoalWithItsReport() = scope.runTest {
        text.onShow = { if (it == ChannelOutput.Said("On it.")) awaitCancellation() }
        val environment = environment(say("On it."), listSms, finish(1))
        val engine = engine(environment)
        engine.restore()
        engine.submit(typed("Read my latest SMS"))
        runCurrent()

        engine.submit(input(InputChannel.TEXT, InputIntent.Stop))
        runCurrent()

        assertNull(engine.view.value.goal)
        val record = engine.view.value.records.single()
        assertEquals(EndReason.CANCELLED, record.end)
        assertEquals(ChannelOutput.Report(1, record.answer!!), text.shown.last())
        assertEquals(1, environment.model.requests.size)
        assertEquals(EndReason.CANCELLED, store.open()!!.records.single().end)
    }

    // A lock pauses the round at its gate; the unlock asks "Continue the goal?", and a yes lets the same round go on. The
    // step that met the gate did nothing, so the model asks for the SMS again.
    @Test
    fun aLockPausesTheGoalAndAfterTheUnlockAYesLetsItGoOn() = scope.runTest {
        val speaking = CompletableDeferred<Unit>()
        text.onShow = { if (it == ChannelOutput.Said("On it.")) speaking.await() }
        val environment = environment(say("On it."), listSms, listSms, finish(3))
        val engine = engine(environment)
        engine.restore()
        engine.submit(typed("Read my latest SMS"))
        runCurrent()

        engine.signal(PhoneSignal.LOCKED)
        speaking.complete(Unit)
        runCurrent()
        assertEquals(GoalPhase.PAUSED, engine.view.value.goal)
        assertTrue(engine.view.value.records.single().data.isEmpty())

        engine.signal(PhoneSignal.UNLOCKED)
        runCurrent()
        assertEquals(QuestionKind.CONTINUE_GOAL, text.asked.last().kind)

        engine.submit(input(InputChannel.TEXT, InputIntent.Answer(ChannelAnswer.Yes)))
        runCurrent()
        assertNull(engine.view.value.goal)
        assertEquals(EndReason.FINISHED, engine.view.value.records.single().end)
        assertEquals(1, environment.starts.size)
    }

    // Plan 7E: a lock pauses the goal while the step that its gate let through still runs, here while Kestrel speaks. The
    // step may still act, so the round keeps the screen's turn and no instant command starts beside it; once the step has
    // ended, the round's next gate is a wait for the owner, and the turn goes. The round takes it again before it acts.
    @Test
    fun aPauseDuringAStepLetsGoOfTheTurnOnceTheStepHasEndedAndTheRoundTakesItAgainBeforeItActs() = scope.runTest {
        val speaking = CompletableDeferred<Unit>()
        text.onShow = { if (it == ChannelOutput.Said("On it.")) speaking.await() }
        val screen = FakeScreenTurn()
        val environment = environment(say("On it."), listSms, listSms, finish(3))
        val engine = engine(environment, screen = screen)
        engine.restore()
        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        assertTrue(screen.holds)

        engine.signal(PhoneSignal.LOCKED)
        runCurrent()
        assertEquals(GoalPhase.PAUSED, engine.view.value.goal)
        assertTrue(screen.holds)
        val takes = screen.takes
        speaking.complete(Unit)
        runCurrent()
        assertFalse(screen.holds)
        assertEquals(takes, screen.takes)
        assertTrue(engine.view.value.records.single().data.isEmpty())

        engine.signal(PhoneSignal.UNLOCKED)
        runCurrent()
        engine.submit(input(InputChannel.TEXT, InputIntent.Answer(ChannelAnswer.Yes)))
        runCurrent()
        assertTrue(screen.takes > takes)
        assertEquals(EndReason.FINISHED, engine.view.value.records.single().end)
        assertFalse(screen.holds)
    }

    // Plan 7D's re-review, R5-1: a lock pauses the goal while its round waits inside a model call, with the screen's turn
    // held. No step can act then, so the engine has the round let go of the turn at once, and the owner's instant commands
    // work during the pause, before the model answers.
    @Test
    fun aPauseDuringAModelCallLetsGoOfTheTurnAtOnce() = scope.runTest {
        val answering = CompletableDeferred<Unit>()
        val screen = FakeScreenTurn()
        val environment = environment(listSms, listSms, finish(2)).apply { beforeCall = { if (it == 1) answering.await() } }
        val engine = engine(environment, screen = screen)
        engine.restore()
        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        assertTrue(screen.holds)

        engine.signal(PhoneSignal.LOCKED)
        runCurrent()

        assertEquals(GoalPhase.PAUSED, engine.view.value.goal)
        assertFalse(screen.holds)
        answering.complete(Unit)
        runCurrent()
        assertFalse(screen.holds)
        assertTrue(engine.view.value.records.single().data.isEmpty())
    }

    // Plan 7E: while the goal's round waits behind the owner's instant command, the view says so, for the goal
    // notification, because the bar belongs to the command then; once the round has the turn, it no longer does.
    @Test
    fun theViewSaysWhileTheRoundWaitsBehindTheOwnersCommand() = scope.runTest {
        val screen = FakeScreenTurn()
        val command = CompletableDeferred<Unit>()
        screen.command = command
        val engine = engine(environment(listSms, finish(1)), screen = screen)
        engine.restore()
        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        assertEquals(GoalPhase.ACTING, engine.view.value.goal)
        assertTrue(engine.view.value.waitsBehindCommand)

        command.complete(Unit)
        runCurrent()
        assertFalse(engine.view.value.waitsBehindCommand)
        assertEquals(EndReason.FINISHED, engine.view.value.records.single().end)
    }

    // A save that fails is reported; the view still shows the step, and the next step saves the conversation again.
    @Test
    fun aFailedSaveStillShowsTheStepAndTheNextStepSavesAgain() = scope.runTest {
        val engine = engine(environment(listSms, finish(1)), report = { _, error -> failures += error })
        engine.restore()
        archive.failures["write"] = IOException("The disk is full")

        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        assertEquals(EndReason.FINISHED, engine.view.value.records.single().end)
        assertTrue(failures.isNotEmpty() && failures.all { it is IOException })

        archive.failures.clear()
        engine.signal(PhoneSignal.UNLOCKED)
        runCurrent()
        assertEquals(EndReason.FINISHED, ArchivedConversationStore(archive, backgroundScope) {}.open()!!.records.single().end)
    }

    // An effect that fails still gives the machine the end that it waits for: here a held goal whose record is missing.
    @Test
    fun aGoalWhoseResumeFailsEndsAsAnError() = scope.runTest {
        val now = ManualConversationClock.BASE
        val record = CommandRecord(1, now - 60_000, InputChannel.VOICE, "Archive my newsletters")
        store.save(ConversationSnapshot(
            ConversationId("0123456789abcdef0123456789abcdef"), now - 60_000, now - 30_000, open = true, testMode = false,
            records = listOf(record), goal = GoalState(2, GoalPhase.ACTING, now - 30_000),
        ))
        val environment = environment()
        val engine = engine(environment, ArchivedConversationStore(archive, backgroundScope) {}, report = { _, error -> failures += error })
        assertEquals(RestoreResult.GOAL_HELD, engine.restore())
        runCurrent()

        engine.submit(input(InputChannel.VOICE, InputIntent.Answer(ChannelAnswer.Yes)))
        runCurrent()

        assertNull(engine.view.value.goal)
        assertTrue(failures.single() is IllegalStateException)
        assertEquals(ChannelOutput.Report(1, ConversationTexts.COULD_NOT), voice.shown.last())
        assertEquals(EndReason.KESTREL_ERROR, engine.outcome(1)!!.report.end)
        assertTrue(environment.starts.isEmpty())
    }

    @Test
    fun theViewShowsTheSafetyPagesTestModeWhileNoConversationIsOpen() = scope.runTest {
        val engine = engine(environment())
        engine.restore()
        runCurrent()

        assertNull(engine.view.value.id)
        assertFalse(engine.view.value.testMode)
    }

    // The store's writes run beside the engine's loop: while a save waits for the store, the loop shows the step and takes
    // the owner's "stop".
    @Test
    fun aSaveThatWaitsForTheStoreHoldsUpNoInput() = scope.runTest {
        val gated = GatedStore(store)
        val engine = engine(environment(listSms, finish(1)), gated)
        engine.restore()
        val gate = CompletableDeferred<Unit>()
        gated.gate = gate

        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        assertEquals(GoalPhase.ACTING, engine.view.value.goal)

        engine.submit(input(InputChannel.TEXT, InputIntent.Stop))
        runCurrent()
        assertNull(engine.view.value.goal)
        assertEquals(EndReason.CANCELLED, engine.view.value.records.single().end)

        gate.complete(Unit)
        runCurrent()
        assertEquals(EndReason.CANCELLED, store.open()!!.records.single().end)
    }

    // A switch that the store could not make ends as Kestrel's error with its words, also for the hooks' outcome, and the
    // conversation stays closed.
    @Test
    fun aSwitchThatTheStoreCannotMakeEndsAsAnError() = scope.runTest {
        val failing = object : ConversationStore by store {
            override suspend fun reopen(id: ConversationId, closing: ConversationSnapshot?, nowWall: Long, testMode: Boolean): ConversationSnapshot? =
                throw IOException("The disk is full")
        }
        val engine = engine(environment(listSms, finish(1)), failing, report = { _, error -> failures += error })
        engine.restore()
        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        val past = engine.view.value.id!!
        engine.endQuietTime()
        runCurrent()

        engine.submit(input(InputChannel.TEXT, InputIntent.ContinueConversation(past)))
        runCurrent()

        val outcome = engine.outcome(engine.view.value.command)!!
        assertEquals(EndReason.KESTREL_ERROR, outcome.report.end)
        assertEquals(ConversationTexts.COULD_NOT, outcome.answer)
        assertEquals(ChannelOutput.Report(engine.view.value.command, ConversationTexts.COULD_NOT), text.shown.last())
        assertTrue(failures.single() is IOException)
        assertNull(engine.view.value.id)
    }

    // Keeps back what is dispatched while [holding] until [release], so that a test can run other work before it; delays go
    // to [inner].
    @OptIn(InternalCoroutinesApi::class)
    private class HeldDispatcher(private val inner: CoroutineDispatcher) : CoroutineDispatcher(), Delay by inner as Delay {
        var holding = false
        private val held = mutableListOf<Pair<CoroutineContext, Runnable>>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (holding) held += context to block else inner.dispatch(context, block)
        }

        fun release() {
            held.forEach { (context, block) -> inner.dispatch(context, block) }
            held.clear()
        }
    }

    // A store whose saves wait for [gate], whose switches wait for [reopenGate] and whose deletion waits for [deleting],
    // while they are set; [calls] names each save and deletion once it is done.
    private class GatedStore(private val inner: ConversationStore) : ConversationStore by inner {
        var gate: CompletableDeferred<Unit>? = null
        var reopenGate: CompletableDeferred<Unit>? = null
        var deleting: CompletableDeferred<Unit>? = null
        val calls = mutableListOf<String>()

        override suspend fun reopen(id: ConversationId, closing: ConversationSnapshot?, nowWall: Long, testMode: Boolean): ConversationSnapshot? {
            reopenGate?.await()
            return inner.reopen(id, closing, nowWall, testMode)
        }

        override suspend fun save(snapshot: ConversationSnapshot) {
            gate?.await()
            inner.save(snapshot)
            calls += "save"
        }

        override suspend fun deleteAll() {
            deleting?.await()
            inner.deleteAll()
            calls += "deleteAll"
        }
    }

    // A steer with a one-time code that comes in the goal's last step is named in the final words in its record's form only:
    // neither the record, the archive's payload nor the search of past conversations holds the code (review of plan 7B:
    // C1).
    @Test
    fun aLateSteerWithAOneTimeCodeLeavesTheCodeNowhere() = scope.runTest {
        val environment = environment(listSms, finish(1))
        val engine = engine(environment)
        environment.afterCall = { calls -> if (calls == 2) engine.submit(typed("The code is 482913")) }
        engine.restore()

        engine.submit(typed("Read my latest SMS"))
        runCurrent()

        val id = engine.view.value.id!!
        val record = engine.view.value.records.single()
        assertTrue(record.answer!!.contains("a one-time code"))
        assertFalse(record.answer!!.contains("482913"))
        assertTrue(record.lines.none { "482913" in it.text })
        assertFalse("482913" in archive.payload(id.value))
        val found = HistorySearch(store, { 30 }, clock).search(HistoryQuery("latest SMS")).matches.single()
        assertFalse(found.answer!!.contains("482913"))
        assertFalse("482913" in engine.outcome(1)!!.answer!!)
    }

    // "Stop" that overtakes a touch ends the goal as cancelled, and the steering that waited is named in the final words, in
    // its record's form (review of plan 7B: I1 and C1).
    @Test
    fun aStopThatOvertakesATouchEndsTheGoalAsCancelledAndNamesTheWaitingSteer() = scope.runTest {
        text.onShow = { if (it == ChannelOutput.Said("On it.")) awaitCancellation() }
        val engine = engine(environment(say("On it."), listSms, finish(1)))
        engine.restore()
        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        engine.submit(typed("The code is 482913"))
        runCurrent()
        val id = engine.view.value.id!!

        engine.touched()
        engine.submit(input(InputChannel.TEXT, InputIntent.Stop))
        runCurrent()

        assertNull(engine.view.value.goal)
        val record = engine.view.value.records.single()
        assertEquals(EndReason.CANCELLED, record.end)
        assertTrue(record.answer!!.contains("a one-time code"))
        assertFalse("482913" in archive.payload(id.value))
        assertEquals(EndReason.CANCELLED, engine.outcome(1)!!.report.end)
    }

    // The call backstop that comes while a touch interrupts the round still ends the goal, and its end keeps the backstop's
    // sentence (review of plan 7B: n1).
    @Test
    fun theCallBackstopEndsATouchedGoalWithItsSentence() = scope.runTest {
        text.onShow = { if (it == ChannelOutput.Said("On it.")) awaitCancellation() }
        val engine = engine(environment(say("On it."), listSms, finish(1)))
        engine.restore()
        engine.submit(typed("Read my latest SMS"))
        runCurrent()

        engine.touched()
        engine.signal(PhoneSignal.AGENT_CALL)
        runCurrent()

        assertNull(engine.view.value.goal)
        val record = engine.view.value.records.single()
        assertEquals(EndReason.CANCELLED, record.end)
        assertTrue(record.answer!!.startsWith(ConversationTexts.CALL_STARTED))
    }

    // A "stop" while the store switches to a past conversation cancels the switch: the conversation that was open stays
    // open, and the past one is kept as it was (review of plan 7B: M3).
    @Test
    fun aStopWhileTheStoreSwitchesKeepsTheOpenConversation() = scope.runTest {
        val gated = GatedStore(store)
        val engine = engine(environment(listSms, finish(1), listSms, finish(1)), gated)
        engine.restore()
        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        val past = engine.view.value.id!!
        engine.endQuietTime()
        runCurrent()
        engine.submit(typed("Read it again"))
        runCurrent()
        val current = engine.view.value.id!!
        val gate = CompletableDeferred<Unit>()
        gated.reopenGate = gate

        engine.submit(input(InputChannel.TEXT, InputIntent.ContinueConversation(past)))
        runCurrent()
        engine.submit(input(InputChannel.TEXT, InputIntent.Stop))
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(current, engine.view.value.id)
        assertEquals(current, store.open()!!.id)
        val kept = store.get(past)!!
        assertFalse(kept.open)
        assertNull(kept.continuedAtWall)
        assertEquals(EndReason.CANCELLED, engine.outcome(engine.view.value.command)!!.report.end)
    }

    // A change of test mode while "Remember that …" asks waits for the request: the conversation closes with the record's
    // end, and the next command runs in a new conversation under the new test mode.
    @Test
    fun aChangeOfTestModeWaitsForAMemoryRequestAndClosesAfterItsEnd() = scope.runTest {
        val engine = engine(environment(listSms, finish(1)))
        engine.restore()
        engine.submit(input(InputChannel.TEXT, InputIntent.Remember("the gate code", "4321")))
        runCurrent()
        val id = engine.view.value.id!!
        assertEquals(QuestionKind.YES_NO, text.asked.last().kind)

        testMode.value = true
        runCurrent()
        assertEquals(id, engine.view.value.id)

        engine.submit(input(InputChannel.TEXT, InputIntent.Answer(ChannelAnswer.Yes)))
        runCurrent()
        assertNull(engine.view.value.id)
        val record = store.get(id)!!.records.single()
        assertEquals(EndReason.FINISHED, record.end)
        assertEquals(ConversationTexts.REMEMBERED, record.answer)

        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        assertTrue(engine.view.value.testMode)
        assertEquals(EndReason.FINISHED, engine.view.value.records.single().end)
    }

    // The screen comes on after a sleep: the view shows at once that the quiet time ended, for the bar and the notification.
    @Test
    fun theScreenComingOnShowsAQuietTimeThatEndedInTheSleep() = scope.runTest {
        val engine = engine(environment(listSms, finish(1)))
        engine.restore()
        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        assertNotNull(engine.view.value.id)

        clock.sleep(10 * 60_000L)
        engine.signal(PhoneSignal.SCREEN_ON)
        runCurrent()

        assertNull(engine.view.value.id)
        assertEquals(QuietClock.Off, engine.view.value.quiet)
    }

    // "Continue the conversation about X" reads the store only after every write queued before it: here the close of the
    // conversation that it looks for, which waits for the store.
    @Test
    fun theSearchForAPastConversationWaitsForTheWritesBeforeIt() = scope.runTest {
        val gated = GatedStore(store)
        val engine = engine(environment(listSms, finish(1)), gated)
        engine.restore()
        engine.submit(spoken("Read my latest SMS"))
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        gated.gate = gate

        engine.endQuietTime()
        engine.submit(input(InputChannel.VOICE, InputIntent.ContinuePast("latest SMS")))
        runCurrent()
        assertTrue(voice.asked.none { it.kind == QuestionKind.SWITCH })

        gate.complete(Unit)
        runCurrent()
        assertEquals(QuestionKind.SWITCH, voice.asked.last().kind)
    }

    // "Delete history" deletes only after every write queued before it, so that a save that waited is deleted too.
    @Test
    fun deleteHistoryWaitsForTheWritesBeforeIt() = scope.runTest {
        text.answers += ChannelAnswer.Yes
        val gated = GatedStore(store)
        val engine = engine(environment(listSms, finish(1)), gated)
        engine.restore()
        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        gated.gate = gate

        engine.endQuietTime()
        engine.submit(input(InputChannel.TEXT, InputIntent.DeleteHistory))
        runCurrent()
        assertFalse("deleteAll" in gated.calls)

        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("save", "deleteAll"), gated.calls.takeLast(2))
        assertTrue(archive.rows.isEmpty())
    }

    // Plan 7F: a Continue tapped on a hand-over's card is the owner's act of the moment of his tap, as a spoken "Kestrel,
    // continue" is: the round that goes on names the tap's number at its next screen turn. So it may cancel an instant
    // command that the owner started before his tap, and waits for one that he started after it (plan 7D's ScreenTurns:
    // `after > since`).
    @Test
    fun aContinueTappedOnAHandOversCardNamesTheTapsOwnNumber() = scope.runTest {
        val environment = FakeGoalEnvironment(listOf(listSms, listSms, finish(1))).apply {
            data.answer("list_sms", DataResult.NeedsAccess(AccessKind.NOTIFICATIONS), sms)
        }
        text.handOverEnds += HandOverEnd.CONTINUED
        // A command that the owner started before his tap holds the screen since the newest number then.
        var before = 0L
        var tap = 0L
        text.taps = {
            before = inputs
            (++inputs).also { tap = it }
        }
        val afters = mutableListOf<Long?>()
        val engine = engine(environment, screen = FakeScreenTurn { after -> afters += after; false })
        engine.restore()
        val command = typed("Read my latest SMS")
        engine.submit(command)
        runCurrent()

        assertEquals(1, text.handOvers.size)
        assertEquals(listOf(command.id, tap), afters.filterNotNull())
        val after = afters.filterNotNull().last()
        // Newer than the command that started before the tap: the round may cancel it.
        assertTrue(after > before)
        // Not newer than one that starts after the tap, which holds the screen since the tap's number or a later one: the
        // round waits for it.
        assertFalse(after > tap)
        assertEquals(EndReason.FINISHED, engine.view.value.records.single().end)
    }

    // Plan 7G's hook expire-typed: the quiet time ends only for an open conversation that the check accepts, checked on the
    // loop; without one, or when the check refuses, nothing changes.
    @Test
    fun theQuietTimeEndsOnlyForAnOpenConversationThatTheCheckAccepts() = scope.runTest {
        val engine = engine(environment(listSms, finish(1)))
        engine.restore()
        assertFalse(engine.endQuietTimeIf { true })
        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        val open = engine.view.value.id!!
        val seen = mutableListOf<ConversationId>()

        assertFalse(engine.endQuietTimeIf { seen += it; false })
        runCurrent()
        assertEquals(open, engine.view.value.id)
        assertTrue(engine.endQuietTimeIf { seen += it; true })
        runCurrent()
        assertNull(engine.view.value.id)
        assertEquals(listOf(open, open), seen)
    }

    // Design 9.5 of sub-project 7: a past conversation is deleted, or all of them, and never the open one.
    @Test
    fun aPastConversationIsDeletedFromTheListButTheOpenOneIsNot() = scope.runTest {
        val engine = engine(environment(listSms, finish(1), listSms, finish(1), listSms, finish(1)))
        engine.restore()
        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        val first = engine.view.value.id!!
        advanceTimeBy(61_000)
        runCurrent()
        engine.submit(typed("Read my latest SMS again"))
        runCurrent()
        val second = engine.view.value.id!!
        advanceTimeBy(61_000)
        runCurrent()
        engine.submit(typed("And once more"))
        runCurrent()
        val open = engine.view.value.id!!

        assertEquals(PastDeletion.OPEN, engine.deletePast(open))
        assertEquals(PastDeletion.DELETED, engine.deletePast(first))
        assertEquals(listOf(second), store.list().map { it.id })
        assertEquals(PastDeletion.DELETED, engine.deleteAllPast())
        assertTrue(store.list().isEmpty())
        assertEquals(open, store.open()!!.id)
        assertEquals(open, engine.view.value.id)
    }

    // Plan 7F's device tests shorten the engine's timers through its seam, which shortens the deadline itself, not only the
    // wake-up; the machine's lengths stay.
    @Test
    fun theTimersTakeTheirLengthsThroughTheSeam() = scope.runTest {
        val engine = engine(environment(listSms, finish(1)), timerMillis = { _, millis -> millis / 60 })
        engine.restore()
        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        assertNotNull(engine.view.value.id)

        advanceTimeBy(1_001)
        runCurrent()

        assertNull(engine.view.value.id)
    }

    // Review N2 and R4 of plan 7F: "Delete all" waits for the index of past conversations beside the engine's inbox, so the
    // conversation goes on meanwhile, and the open conversation stays.
    @Test
    fun theConversationGoesOnWhileDeleteAllWaitsForTheIndex() = scope.runTest {
        val gate = CompletableDeferred<Unit>()
        archive.readShardsGate = gate
        val engine = engine(environment(listSms, finish(1)))
        engine.restore()
        val deleting = async { engine.deleteAllPast() }
        runCurrent()

        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        assertFalse(deleting.isCompleted)
        assertEquals(EndReason.FINISHED, engine.view.value.records.single().end)
        val open = engine.view.value.id!!

        gate.complete(Unit)
        runCurrent()
        assertEquals(PastDeletion.DELETED, deleting.await())
        assertEquals(open, store.open()!!.id)
        assertEquals(open, engine.view.value.id)
    }

    // Review B-M4 of plan 7F: the engine says that "Delete all" runs, so that the settings page shows it also after the owner
    // left it and came back, and a second ask while it runs waits for it and deletes nothing again.
    @Test
    fun aSecondDeleteAllWhileOneRunsWaitsForItAndTheEngineSaysThatItRuns() = scope.runTest {
        val gate = CompletableDeferred<Unit>()
        archive.readShardsGate = gate
        val engine = engine(environment())
        engine.restore()
        assertEquals(AllPastDeletion.Idle, engine.allPastDeletion.value)
        val first = async { engine.deleteAllPast() }
        runCurrent()
        assertEquals(AllPastDeletion.Running, engine.allPastDeletion.value)
        val second = async { engine.deleteAllPast() }
        runCurrent()

        gate.complete(Unit)
        runCurrent()
        assertEquals(PastDeletion.DELETED, first.await())
        assertEquals(PastDeletion.DELETED, second.await())
        assertEquals(1, archive.calls.count { it == "deleteClosed" })
        assertEquals(AllPastDeletion.Ended(PastDeletion.DELETED), engine.allPastDeletion.value)
    }

    // Design 9.5 of sub-project 7 and plan 7F's review M1: "Delete all" keeps every conversation that is open at some
    // moment between the owner's ask and its transaction: the one that was open when he asked, and one that he started
    // after it, also when both close while the deletion waits for the index; it deletes the others.
    @Test
    fun deleteAllKeepsEveryConversationThatWasOpenBeforeItsTransactionAlsoWhenItClosedMeanwhile() = scope.runTest {
        val gate = CompletableDeferred<Unit>()
        archive.readShardsGate = gate
        val engine = engine(environment(listSms, finish(1), listSms, finish(1), listSms, finish(1)))
        engine.restore()
        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        val past = engine.view.value.id!!
        engine.endQuietTime()
        runCurrent()
        engine.submit(typed("Read my latest SMS again"))
        runCurrent()
        val open = engine.view.value.id!!
        val deleting = async { engine.deleteAllPast() }
        runCurrent()
        engine.endQuietTime()
        runCurrent()
        engine.submit(typed("And once more"))
        runCurrent()
        val started = engine.view.value.id!!
        engine.endQuietTime()
        runCurrent()
        assertNull(engine.view.value.id)

        gate.complete(Unit)
        runCurrent()
        assertEquals(PastDeletion.DELETED, deleting.await())
        assertEquals(setOf(open, started), store.list().map { it.id }.toSet())
        assertNull(store.get(past))
    }

    // Plan 7F, Task 3's review M1: the conversation that is open when the owner asks stays, also when it closes before the
    // deletion's coroutine starts, as it can on the app's thread pool.
    @Test
    fun deleteAllKeepsTheConversationThatWasOpenAtTheAskAlsoWhenItClosesBeforeTheDeletionStarts() = scope.runTest {
        val dispatcher = HeldDispatcher(StandardTestDispatcher(testScheduler))
        val engine = engine(environment(listSms, finish(1), listSms, finish(1)), engineScope = CoroutineScope(backgroundScope.coroutineContext + dispatcher))
        engine.restore()
        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        val past = engine.view.value.id!!
        engine.endQuietTime()
        runCurrent()
        engine.submit(typed("Read my latest SMS again"))
        runCurrent()
        val open = engine.view.value.id!!

        dispatcher.holding = true
        val deleting = async(start = CoroutineStart.UNDISPATCHED) { engine.deleteAllPast() }
        dispatcher.holding = false
        engine.endQuietTime()
        runCurrent()
        assertNull(engine.view.value.id)

        dispatcher.release()
        runCurrent()
        assertEquals(PastDeletion.DELETED, deleting.await())
        assertEquals(listOf(open), store.list().map { it.id })
        assertNull(store.get(past))
    }

    // Plan 7F's review L1: the deletion of the conversation that a running switch opens finds it open and leaves it; without
    // the check, the writer would open it and then delete its open row.
    @Test
    fun deletingTheConversationThatARunningSwitchOpensLeavesIt() = scope.runTest {
        val reopening = CompletableDeferred<Unit>()
        val gated = object : ConversationStore by store {
            override suspend fun reopen(id: ConversationId, closing: ConversationSnapshot?, nowWall: Long, testMode: Boolean): ConversationSnapshot? {
                reopening.await()
                return store.reopen(id, closing, nowWall, testMode)
            }
        }
        val engine = engine(environment(listSms, finish(1)), gated)
        engine.restore()
        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        val past = engine.view.value.id!!
        engine.endQuietTime()
        runCurrent()

        engine.submit(input(InputChannel.TEXT, InputIntent.ContinueConversation(past)))
        runCurrent()
        val deleting = async { engine.deletePast(past) }
        runCurrent()
        reopening.complete(Unit)
        runCurrent()

        assertEquals(PastDeletion.OPEN, deleting.await())
        assertEquals(past, engine.view.value.id)
        assertEquals(past, store.open()!!.id)
    }
}
