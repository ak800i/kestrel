package dev.kestrel.conversation.engine

import dev.kestrel.agent.EndReason
import dev.kestrel.agent.TaskRequest
import dev.kestrel.agent.goal.Checklist
import dev.kestrel.agent.goal.ChecklistItem
import dev.kestrel.agent.goal.GoalLimits
import dev.kestrel.agent.goal.ItemState
import dev.kestrel.agent.port.DataField
import dev.kestrel.agent.port.DataLine
import dev.kestrel.agent.port.DataResult
import dev.kestrel.agent.port.PermissionEnd
import dev.kestrel.agent.testing.ScriptedModel
import dev.kestrel.conversation.ConversationTexts
import dev.kestrel.conversation.channel.ChannelOutput
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.goal.GoalRunner
import dev.kestrel.conversation.goal.ScreenTurn
import dev.kestrel.conversation.local.LocalRequests
import dev.kestrel.conversation.machine.ConversationEvent
import dev.kestrel.conversation.machine.ConversationState
import dev.kestrel.conversation.machine.HoldReason
import dev.kestrel.conversation.machine.MachineStep
import dev.kestrel.conversation.machine.QuietClock
import dev.kestrel.conversation.model.ChannelAnswer
import dev.kestrel.conversation.model.CommandRecord
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.ConversationSettings
import dev.kestrel.conversation.model.ConversationSnapshot
import dev.kestrel.conversation.model.GoalPhase
import dev.kestrel.conversation.model.GoalState
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.InputIntent
import dev.kestrel.conversation.model.OwnerInput
import dev.kestrel.conversation.model.PendingSteer
import dev.kestrel.conversation.model.PhoneSignal
import dev.kestrel.conversation.model.ProcessEnd
import dev.kestrel.conversation.store.ArchivedConversationStore
import dev.kestrel.conversation.store.ConversationStore
import dev.kestrel.conversation.store.HistorySearch
import dev.kestrel.conversation.testing.FakeChannel
import dev.kestrel.conversation.testing.FakeGoalEnvironment
import dev.kestrel.conversation.testing.ManualConversationClock
import dev.kestrel.conversation.testing.MemoryArchive
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.Part
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Whole conversations on the JVM (design 15.1 of sub-project 7), with the scripted model, fake channels and the in-memory archive. */
class ConversationEngineTest {
    private val voice = FakeChannel(InputChannel.VOICE)
    private val text = FakeChannel(InputChannel.TEXT)
    private val archive = MemoryArchive()
    private val scope = TestScope()
    private val store = ArchivedConversationStore(archive, scope.backgroundScope) {}
    private val settings = MutableStateFlow(ConversationSettings())
    private val testMode = MutableStateFlow(false)
    private val voiceOn = MutableStateFlow(true)
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
    ): ConversationEngine {
        val clock = ManualConversationClock({ testScheduler.currentTime })
        val search = HistorySearch(store, { settings.value.keptDays }, clock)
        val goals = GoalRunner(environment, ScreenTurn { }, search, clock) { _, error -> throw error }
        val local = LocalRequests(environment.memory, store, search, { }, goals::saveRoutine, clock) { _, error -> throw error }
        val engine = ConversationEngine(
            backgroundScope, store, goals, local, mapOf(InputChannel.VOICE to voice, InputChannel.TEXT to text), { PermissionEnd.GRANTED },
            settings, testMode, voiceOn, clock, { _, error -> throw error }, onStep = onStep,
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
        assertEquals(Restored.NOTHING, engine.restore())

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
        voiceOn.value = false
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
        assertEquals(Restored.OPEN, second.restore())
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

        assertEquals(Restored.GOAL_HELD, engine.restore())
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

        assertEquals(Restored.GOAL_ENDED, engine.restore(ProcessEnd.OwnerStopped(now - 10_000)))
        runCurrent()

        // Plan 7E: no round runs, nothing asks "Continue the goal?", and the record ends as "stop" ends it.
        assertNull(engine.view.value.goal)
        assertTrue(voice.asked.isEmpty() && text.asked.isEmpty())
        assertTrue(environment.starts.isEmpty())
        assertEquals(EndReason.CANCELLED, engine.view.value.records.single().end)
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
}
