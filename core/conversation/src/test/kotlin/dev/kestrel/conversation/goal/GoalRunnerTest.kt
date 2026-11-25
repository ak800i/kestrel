package dev.kestrel.conversation.goal

import dev.kestrel.agent.AgentPorts
import dev.kestrel.agent.EndReason
import dev.kestrel.agent.TaskCancellation
import dev.kestrel.agent.Ledger
import dev.kestrel.agent.Interruption
import dev.kestrel.agent.TaskReport
import dev.kestrel.agent.goal.Checklist
import dev.kestrel.agent.goal.ChecklistItem
import dev.kestrel.agent.goal.GoalLimits
import dev.kestrel.agent.goal.HistoryQuery
import dev.kestrel.agent.goal.ItemState
import dev.kestrel.agent.goal.Steer
import dev.kestrel.agent.goal.SteerChannel
import dev.kestrel.agent.goal.TraceEntry
import dev.kestrel.agent.port.Answer
import dev.kestrel.agent.port.DataField
import dev.kestrel.agent.port.DataLine
import dev.kestrel.agent.port.DataResult
import dev.kestrel.agent.port.Gate
import dev.kestrel.agent.port.OwnerPort
import dev.kestrel.agent.port.TaskLog
import dev.kestrel.agent.testing.FakeOwner
import dev.kestrel.agent.testing.ScreenBuilder
import dev.kestrel.agent.testing.ScriptedModel
import dev.kestrel.conversation.ConversationTexts
import dev.kestrel.conversation.context.SteeringMessage
import dev.kestrel.conversation.model.CommandRecord
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.RecordRef
import dev.kestrel.conversation.model.RoundEnd
import dev.kestrel.conversation.store.ArchivedConversationStore
import dev.kestrel.conversation.store.ConversationStore
import dev.kestrel.conversation.store.HistorySearch
import dev.kestrel.conversation.store.Searchable
import dev.kestrel.conversation.testing.FakeGoalEnvironment
import dev.kestrel.conversation.testing.FakeScreenTurn
import dev.kestrel.conversation.testing.ManualConversationClock
import dev.kestrel.conversation.testing.MemoryArchive
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.Part
import dev.kestrel.safety.Category
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GoalRunnerTest {
    private class Listener : GoalListener {
        val steps = mutableListOf<Int>()
        val traces = mutableListOf<TraceEntry>()
        val checklists = mutableListOf<Checklist>()
        val allowed = mutableSetOf<Category>()
        val ends = mutableListOf<RoundEnd>()
        var steering = 0
        var touches = 0
        val waits = mutableListOf<Boolean>()

        override fun stepStarted(step: Int) {
            steps += step
        }

        override suspend fun traced(entry: TraceEntry) {
            traces += entry
        }

        override suspend fun checklist(checklist: Checklist) {
            checklists += checklist
        }

        override fun allowed(categories: Set<Category>) {
            allowed += categories
        }

        override fun steeringChanged() {
            steering++
        }

        override fun touched() {
            touches++
        }

        override fun waitsBehindCommand(waits: Boolean) {
            this.waits += waits
        }

        override fun ended(end: RoundEnd) {
            ends += end
        }
    }

    private val id = ConversationId("0123456789abcdef0123456789abcdef")
    private val sms = DataResult.Ok(listOf(DataLine("sms 1", listOf(DataField("from", "Mila"), DataField("body", "Ambar at 8")))))
    private val listSms = ScriptedModel.call("list_sms", null, null, null, "limit" to 1)
    private val listener = Listener()

    private fun finish(step: Int) = ScriptedModel.call("finish", null, null, null, "summary" to "Mila wrote: Ambar at 8.", "evidence" to listOf(mapOf("step" to step)))

    private fun record(text: String = "Read my latest SMS") = CommandRecord(1, 0, InputChannel.TEXT, text)

    private fun plan(record: CommandRecord, testMode: Boolean = false, stored: suspend () -> Unit = {}) = RoundPlan(
        record, emptyList(), record.request, null, testMode, null, GoalLimits(), emptyMap(), RecordRef(id, record.number), ManualConversationClock.BELGRADE,
        waiting = 0, stored = stored,
    )

    private fun TestScope.runner(
        environment: GoalEnvironment,
        screen: ScreenTurn = FakeScreenTurn { false },
        store: ConversationStore = ArchivedConversationStore(MemoryArchive(), backgroundScope) {},
    ): GoalRunner {
        val clock = ManualConversationClock({ testScheduler.currentTime })
        return GoalRunner(environment, screen, HistorySearch(store, { 30 }, clock), clock) { _, error -> throw error }
    }

    private fun environment(vararg replies: kotlinx.serialization.json.JsonObject) = FakeGoalEnvironment(replies.toList()).apply { data.answer("list_sms", sms) }

    // A goal whose finish offers a routine (design D23 of sub-project 5): a finish with its signature came once before.
    private fun offering(): FakeGoalEnvironment {
        val start = ScreenBuilder.screen("dev.kestrel.testapp", "Kestrel fixtures") { button("Open", 300) }
        val opened = ScreenBuilder.screen("dev.kestrel.testapp", "Kestrel fixtures") { text("Opened", 300) }
        val tap = ScriptedModel.call("tap", "Open", null, null, "reason" to "open it", "category" to "reading")
        val done = ScriptedModel.call(
            "finish", null, null, null,
            "summary" to "Opened.", "evidence" to listOf(mapOf("target" to mapOf("label" to "Opened"))), "signature" to "fixtures.open.button",
        )
        return FakeGoalEnvironment(listOf(tap, done), first = start).apply {
            offersRoutines = true
            screen.onTap("Open", opened)
            memory.signatures["fixtures.open.button"] = 1
        }
    }

    // The fake environment, with each round's ports or release changed by [change].
    private class Changed(private val inner: FakeGoalEnvironment, private val change: (RoundSetup.Ready) -> RoundSetup.Ready) : GoalEnvironment by inner {
        override suspend fun prepare(start: RoundStart): RoundSetup = when (val setup = inner.prepare(start)) {
            is RoundSetup.Ready -> change(setup)
            is RoundSetup.Refused -> setup
        }
    }

    private fun RoundSetup.Ready.with(log: TaskLog = ports.log, release: suspend () -> Unit = this.release) = RoundSetup.Ready(
        AgentPorts(ports.screen, ports.eyes, ports.data, ports.owner, ports.model, ports.apps, ports.memory, log, ports.clock, ports.goal, ports.history),
        settings, taskId, knownNames, touches, offersRoutines, release,
    )

    private fun ChatRequest.text(): String = (listOf(context.orEmpty()) + messages.mapNotNull { message ->
        when (message) {
            is ChatMessage.ToolResult -> message.text
            is ChatMessage.User -> message.parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text }
            is ChatMessage.Assistant -> null
        }
    }).joinToString("\n")

    @Test
    fun aRoundRunsWithTheConversationsTestModeAndTracesItsRecord() = runTest {
        val environment = environment(listSms, finish(1))
        val goal = RunningGoal(1, 1, emptyMap(), emptyList(), listener)

        runner(environment).run(this, goal, plan(record("Read my latest SMS and send Ana a message that I'm late"), testMode = true), FakeOwner())
        advanceUntilIdle()

        val end = listener.ends.single()
        assertEquals(EndReason.FINISHED, end.report.end)
        assertEquals(FakeGoalEnvironment.sentenceOf(end.report), end.answer)
        assertEquals(end.answer, end.reply)
        assertEquals(listOf(1, 2), listener.steps)
        assertTrue(environment.model.requests.first().context.orEmpty().contains("Test mode is on"))
        assertTrue(listener.traces.any { it is TraceEntry.Requested })
        assertTrue(listener.traces.any { it is TraceEntry.Data })
        assertEquals(setOf(Category.SENDING), listener.allowed)
        assertEquals(1, environment.released)
        assertEquals(RoundPurpose.GOAL, environment.starts.single().purpose)
    }

    @Test
    fun aRoundReleasesBeforeItsEndReachesTheListener() = runTest {
        val environment = environment(listSms, finish(1))
        val releasedAtEnds = mutableListOf<Int>()
        val ending = object : GoalListener by listener {
            override fun ended(end: RoundEnd) {
                releasedAtEnds += environment.released
                listener.ended(end)
            }
        }

        runner(environment).run(this, RunningGoal(1, 1, emptyMap(), emptyList(), ending), plan(record()), FakeOwner())
        advanceUntilIdle()

        // Design D19 of sub-project 5: the end may start the next round at once, whose touch watcher this release must not stop.
        assertEquals(listOf(1), releasedAtEnds)
        assertEquals(EndReason.FINISHED, listener.ends.single().report.end)
    }

    // A failure while Kestrel's words for the end are built must not leave the goal open: the end still reaches the
    // listener, once, with the sentence for a failure of Kestrel's, and the round has released its ports.
    @Test
    fun aFailingSentenceStillEndsTheRoundOnce() = runTest {
        val environment = environment(listSms, finish(1))
        val failing = object : GoalEnvironment by environment {
            override fun sentence(report: TaskReport): String = error("The sentence failed")
        }
        val clock = ManualConversationClock({ testScheduler.currentTime })
        val failures = mutableListOf<String>()
        val runner = GoalRunner(failing, FakeScreenTurn { false }, HistorySearch(ArchivedConversationStore(MemoryArchive(), backgroundScope) {}, { 30 }, clock), clock) { what, _ -> failures += what }

        runner.run(this, RunningGoal(1, 1, emptyMap(), emptyList(), listener), plan(record()), FakeOwner())
        advanceUntilIdle()

        val end = listener.ends.single()
        assertEquals(EndReason.FINISHED, end.report.end)
        assertEquals(ConversationTexts.COULD_NOT, end.answer)
        assertEquals(ConversationTexts.COULD_NOT, end.reply)
        assertEquals(listOf("Ending a goal's round"), failures)
        assertEquals(1, environment.released)
    }

    @Test
    fun steeringReachesTheModelAtTheNextTurn() = runTest {
        val environment = environment(listSms, finish(1))
        val goal = RunningGoal(1, 1, emptyMap(), emptyList(), listener)
        goal.steer(SteeringMessage.of("Also check Ana's", InputChannel.TEXT))

        runner(environment).run(this, goal, plan(record()), FakeOwner())
        advanceUntilIdle()

        assertTrue(environment.model.requests.first().text().contains("The owner added (typed) before the first step: \"Also check Ana's\""))
        assertTrue(goal.inbox.pending().isEmpty())
        assertEquals(2, listener.steering)
    }

    @Test
    fun steeringThatCameTooLateIsNamedInTheFinalReply() = runTest {
        val environment = environment(listSms, finish(1))
        val goal = RunningGoal(1, 1, emptyMap(), emptyList(), listener)
        environment.afterCall = { calls -> if (calls == 2) goal.steer(SteeringMessage.of("Make it 7:30", InputChannel.VOICE)) }

        runner(environment).run(this, goal, plan(record()), FakeOwner())
        advanceUntilIdle()

        val end = listener.ends.single()
        assertEquals(EndReason.FINISHED, end.report.end)
        assertTrue(end.answer!!.endsWith(" I got 'Make it 7:30' too late to act on it; say it again as a new command."))
        assertTrue(goal.inbox.pending().isEmpty())
    }

    @Test
    fun aTouchInterruptsTheRoundAndTheHeldGoalKeepsItsSteering() = runTest {
        val environment = environment(ScriptedModel.call("say", null, null, null, "text" to "On it."), listSms, finish(1))
        val goal = RunningGoal(1, 1, emptyMap(), emptyList(), listener)
        val owner = FakeOwner()
        owner.onSay = {
            goal.steer(SteeringMessage.of("Also Ana", InputChannel.TEXT))
            goal.interrupt(Interruption.TOUCH)
            yield()
        }

        runner(environment).run(this, goal, plan(record()), owner)
        advanceUntilIdle()

        val end = listener.ends.single()
        assertEquals(EndReason.INTERRUPTED, end.report.end)
        assertEquals(Interruption.TOUCH, end.report.interruption)
        assertEquals(FakeGoalEnvironment.sentenceOf(end.report), end.reply)
        assertEquals(listOf("Also Ana"), goal.inbox.pending().map { it.recorded })
        assertTrue(environment.data.calls.isEmpty())
        assertEquals(1, environment.released)
    }

    @Test
    fun aRefusedStartEndsTheRoundWithTheEnvironmentsSentenceAndEnd() = runTest {
        val environment = environment(listSms)
        environment.refusal = RoundSetup.Refused("This model can't read the screen. Choose another one in Settings.", EndReason.MODEL_UNUSABLE)

        runner(environment).run(this, RunningGoal(1, 1, emptyMap(), emptyList(), listener), plan(record()), FakeOwner())
        advanceUntilIdle()

        val end = listener.ends.single()
        assertEquals(EndReason.MODEL_UNUSABLE, end.report.end)
        assertEquals("This model can't read the screen. Choose another one in Settings.", end.reply)
        assertTrue(environment.model.requests.isEmpty())
    }

    @Test
    fun aHeldGoalEndsWithItsPartialReport() = runTest {
        val checklist = Checklist(listOf(ChecklistItem("c1", "Open Viber", ItemState.DONE, step = 1)))
        val held = record().copy(ledger = listOf("opened Viber"), checklist = checklist)

        val end = runner(environment()).endHeld(3, held, listOf(SteeringMessage.of("Make it 7:30", InputChannel.VOICE)), EndReason.PAUSED_TOO_LONG, null)

        assertEquals(3, end.command)
        assertEquals(EndReason.PAUSED_TOO_LONG, end.report.end)
        assertEquals(listOf("opened Viber"), end.report.ledger)
        assertEquals(checklist, end.report.checklist)
        assertEquals(FakeGoalEnvironment.sentenceOf(end.report) + ConversationTexts.tooLate(listOf(SteeringMessage.of("Make it 7:30", InputChannel.VOICE))), end.answer)
    }

    // The call backstop's sentence replaces the report's reason for a held goal too, as it does for a running round (n1).
    @Test
    fun aHeldGoalThatTheCallBackstopEndsSaysItsSentence() = runTest {
        val held = record().copy(ledger = listOf("opened Viber"))

        val end = runner(environment()).endHeld(3, held, emptyList(), EndReason.CANCELLED, ConversationTexts.CALL_STARTED)

        assertEquals(EndReason.CANCELLED, end.report.end)
        assertEquals("${ConversationTexts.CALL_STARTED} ${Ledger.sentence(listOf("opened Viber"))}", end.answer)
        assertEquals(end.answer, end.reply)
    }

    // A goal that ended for good has given its final words, which named what waited: its inbox takes no later message, so
    // that the conversation can say at once that it came too late (review of Task 10: I3). A held goal's inbox stays open.
    @Test
    fun aGoalThatEndedForGoodTakesNoMoreSteering() = runTest {
        val goal = RunningGoal(1, 1, emptyMap(), emptyList(), listener)

        runner(environment(listSms, finish(1))).run(this, goal, plan(record()), FakeOwner())
        advanceUntilIdle()

        assertEquals(EndReason.FINISHED, listener.ends.single().report.end)
        assertFalse(goal.steer(SteeringMessage.of("Also check Ana's", InputChannel.TEXT)))
        assertTrue(goal.inbox.pending().isEmpty())
    }

    // A round's search of past conversations reads the store only once it holds every write of the conversation queued
    // before the search (the plan's stored, the engine's writer), so that it sees a conversation that was just closed.
    @Test
    fun aRoundsSearchWaitsUntilTheConversationsWritesAreStored() = runTest {
        val environment = environment(listSms, finish(1))
        var read = false
        val inner = ArchivedConversationStore(MemoryArchive(), backgroundScope) {}
        val store = object : ConversationStore by inner {
            override suspend fun searchable(): Searchable {
                read = true
                return inner.searchable()
            }
        }
        val stored = CompletableDeferred<Unit>()
        runner(environment, store = store).run(this, RunningGoal(1, 1, emptyMap(), emptyList(), listener), plan(record(), stored = { stored.await() }), FakeOwner())
        advanceUntilIdle()

        val search = async { environment.starts.single().history.search(HistoryQuery("Mila")) }
        runCurrent()
        assertFalse(read)

        stored.complete(Unit)
        search.await()
        assertTrue(read)
    }

    // A steer that came in the round's last step is named in the final words in its record's form (review of plan 7B: C1):
    // no vault value reaches the record, the archive or a later model.
    @Test
    fun aSteerThatCameTooLateIsNamedInItsRecordsForm() = runTest {
        val environment = environment(listSms, finish(1))
        val goal = RunningGoal(1, 1, emptyMap(), emptyList(), listener)
        environment.afterCall = { calls -> if (calls == 2) goal.steer(SteeringMessage.of("The code is 482913", InputChannel.TEXT)) }

        runner(environment).run(this, goal, plan(record()), FakeOwner())
        advanceUntilIdle()

        val answer = listener.ends.single().answer!!
        assertTrue(answer, answer.contains("a one-time code"))
        assertFalse(answer.contains("482913"))
    }

    @Test
    fun aRoutineOfferSaysTheReportFirstAndTheGoalKeepsItAsItsAnswer() = runTest {
        // The owner gives no answer to the offer.
        val environment = offering()
        val owner = FakeOwner()

        runner(environment).run(this, RunningGoal(1, 1, emptyMap(), emptyList(), listener), plan(record()), owner)
        advanceUntilIdle()

        val end = listener.ends.single()
        assertTrue(end.report.offerRoutine)
        assertEquals(FakeGoalEnvironment.sentenceOf(end.report), end.answer)
        assertNull(end.reply)
        assertEquals(end.answer, owner.said.last())
    }

    // "Stop", a touch or the call backstop ends the offer too; the end keeps the report, which was said already.
    @Test
    fun aStopEndsTheRoutineOffer() = runTest {
        val environment = offering()
        val goal = RunningGoal(1, 1, emptyMap(), emptyList(), listener)
        val owner = FakeOwner().apply { answers += Answer.Yes }
        owner.onAsk = {
            goal.cancel(EndReason.CANCELLED)
            yield()
        }

        runner(environment).run(this, goal, plan(record()), owner)
        advanceUntilIdle()

        val end = listener.ends.single()
        assertEquals(EndReason.FINISHED, end.report.end)
        assertEquals(FakeGoalEnvironment.sentenceOf(end.report), end.answer)
        assertNull(end.reply)
        assertEquals(1, owner.asked.size)
        assertEquals(1, environment.released)
    }

    // The agent logs its end before it reports it, so a stop there comes after the report: the round ends as the agent
    // reported, and a round that was stopped offers no routine.
    @Test
    fun aRoundStoppedAfterItsReportOffersNoRoutine() = runTest {
        val environment = offering()
        val goal = RunningGoal(1, 1, emptyMap(), emptyList(), listener)
        val stopping = Changed(environment) { ready ->
            ready.with(
                log = object : TaskLog {
                    override suspend fun write(kind: TaskLog.Kind, text: String) {
                        environment.log.write(kind, text)
                        if (kind == TaskLog.Kind.END) goal.cancel(EndReason.CANCELLED)
                    }
                },
            )
        }
        val owner = FakeOwner().apply { answers += Answer.Yes }

        runner(stopping).run(this, goal, plan(record()), owner)
        advanceUntilIdle()

        val end = listener.ends.single()
        assertEquals(EndReason.FINISHED, end.report.end)
        assertTrue(end.report.offerRoutine)
        assertEquals(FakeGoalEnvironment.sentenceOf(end.report), end.answer)
        assertEquals(end.answer, end.reply)
        assertTrue(owner.asked.isEmpty())
        assertTrue(owner.said.isEmpty())
        assertEquals(1, environment.released)
    }

    // A touch or a stop can come before the round's coroutine first runs (the engine's loop and the round run on
    // different threads): the round still ends, once, and prepares nothing.
    @Test
    fun aRoundInterruptedBeforeItRunsStillEndsOnce() = runTest {
        val environment = environment(listSms, finish(1))
        val goal = RunningGoal(1, 1, emptyMap(), emptyList(), listener)

        runner(environment).run(this, goal, plan(record()), FakeOwner())
        goal.interrupt(Interruption.TOUCH)
        advanceUntilIdle()

        val end = listener.ends.single()
        assertEquals(EndReason.INTERRUPTED, end.report.end)
        assertEquals(Interruption.TOUCH, end.report.interruption)
        assertEquals(FakeGoalEnvironment.sentenceOf(end.report), end.reply)
        assertTrue(environment.starts.isEmpty())
        assertEquals(0, environment.released)
    }

    @Test
    fun aRoundStoppedBeforeItRunsStillEndsOnceAndNamesItsSteering() = runTest {
        val environment = environment(listSms, finish(1))
        val goal = RunningGoal(1, 1, emptyMap(), listOf(SteeringMessage.of("Make it 7:30", InputChannel.VOICE)), listener)

        runner(environment).run(this, goal, plan(record()), FakeOwner())
        goal.cancel(EndReason.CANCELLED)
        advanceUntilIdle()

        val end = listener.ends.single()
        assertEquals(EndReason.CANCELLED, end.report.end)
        assertEquals(FakeGoalEnvironment.sentenceOf(end.report) + ConversationTexts.tooLate(listOf(SteeringMessage.of("Make it 7:30", InputChannel.VOICE))), end.answer)
        assertTrue(goal.inbox.pending().isEmpty())
        assertTrue(environment.starts.isEmpty())
        assertTrue(environment.model.requests.isEmpty())
    }

    @Test
    fun aStopWhileTheRoundWaitsForTheScreenEndsItBeforeItsAgent() = runTest {
        val environment = environment(listSms, finish(1))
        val goal = RunningGoal(1, 1, emptyMap(), emptyList(), listener)

        runner(environment, FakeScreenTurn { awaitCancellation() }).run(this, goal, plan(record()), FakeOwner())
        advanceUntilIdle()
        assertTrue(listener.ends.isEmpty())
        goal.cancel(EndReason.CANCELLED)
        advanceUntilIdle()

        val end = listener.ends.single()
        assertEquals(EndReason.CANCELLED, end.report.end)
        assertEquals(FakeGoalEnvironment.sentenceOf(end.report), end.answer)
        assertTrue(environment.starts.isEmpty())
        assertEquals(0, environment.released)
    }

    // Whole-plan re-review of plan 7D, N1, and its rounds 2 and 3: a round takes the screen's turn at its start and as every
    // wait for the owner ends, also a pause ("Continue the goal?"), so it waits for an instant command that holds the screen; the turn names the owner's input that lets the round act, once: the input that started it at its
    // start, and the input that resumed it at the first turn after the resume. Every other turn names none, because the
    // round goes on by itself then. A turn that waited counts as a resume, so the run looks at the screen again.
    @Test
    fun aRoundTakesTheScreensTurnBeforeItActsAndNamesTheInputThatLetsItActOnlyOnce() = runTest {
        val start = ScreenBuilder.screen("dev.kestrel.testapp", "Kestrel fixtures") { button("Open", 300) }
        val opened = ScreenBuilder.screen("dev.kestrel.testapp", "Kestrel fixtures") { text("Opened", 300) }
        val tap = ScriptedModel.call("tap", "Open", null, null, "reason" to "open it", "category" to "reading")
        val done = ScriptedModel.call("finish", null, null, null, "summary" to "Opened.", "evidence" to listOf(mapOf("target" to mapOf("label" to "Opened"))))
        val environment = FakeGoalEnvironment(listOf(tap, done), first = start).apply { screen.onTap("Open", opened) }
        val goal = RunningGoal(1, 1, emptyMap(), emptyList(), listener)
        val afters = mutableListOf<Long?>()
        val given = CompletableDeferred<Unit>()
        val screen = FakeScreenTurn { after ->
            afters += after
            if (afters.size == 2) {
                given.await()
                true
            } else {
                false
            }
        }
        val resumed = CompletableDeferred<Unit>()
        var paused = true
        val owner = object : OwnerPort by FakeOwner() {
            override suspend fun gate(): Gate {
                if (!paused) return Gate.GO
                paused = false
                resumed.await()
                return Gate.RESUMED
            }
        }
        goal.actedFor(5)

        runner(environment, screen).run(this, goal, plan(record()), owner)
        advanceUntilIdle()
        assertEquals(listOf<Long?>(5), afters)
        goal.actedFor(9)
        resumed.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf<Long?>(5, 9), afters)
        assertTrue(environment.screen.actions.isEmpty())
        given.complete(Unit)
        advanceUntilIdle()

        assertTrue(afters.drop(2).all { it == null })
        assertEquals(1, environment.screen.actions.size)
        assertEquals(1, listener.ends.size)
    }

    // Plan 7E: a round whose turn waits behind the owner's instant command, at its start or as a wait for the owner ends,
    // tells its listener as the wait starts and once it has the turn, so that the goal notification can say why the goal
    // does not move while the bar belongs to the command.
    @Test
    fun aRoundThatWaitsBehindTheOwnersCommandTellsItsListenerUntilItHasTheTurn() = runTest {
        val screen = FakeScreenTurn()
        val command = CompletableDeferred<Unit>()
        screen.command = command
        val goal = RunningGoal(1, 1, emptyMap(), emptyList(), listener)

        runner(environment(listSms, finish(1)), screen).run(this, goal, plan(record()), FakeOwner())
        advanceUntilIdle()
        assertEquals(listOf(true), listener.waits)
        assertTrue(listener.ends.isEmpty())
        command.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(true, false), listener.waits)
        assertEquals(EndReason.FINISHED, listener.ends.single().report.end)
    }

    // Re-review round 4, R4-1: the round holds the screen's turn while it acts, and lets go of it when it ends, on every
    // path: its end, a refusal and a cancellation while it acts.
    @Test
    fun theRoundLetsGoOfTheTurnOnEveryPath() = runTest {
        val finished = FakeScreenTurn()
        runner(environment(listSms, finish(1)), finished).run(this, RunningGoal(1, 1, emptyMap(), emptyList(), listener), plan(record()), FakeOwner())
        advanceUntilIdle()
        assertEquals(1, listener.ends.size)
        assertFalse(finished.holds)

        val refused = FakeScreenTurn()
        val refusing = environment().apply { refusal = RoundSetup.Refused("No profile.", EndReason.NO_PROFILE) }
        runner(refusing, refused).run(this, RunningGoal(1, 2, emptyMap(), emptyList(), listener), plan(record()), FakeOwner())
        advanceUntilIdle()
        assertEquals(2, listener.ends.size)
        assertFalse(refused.holds)

        val cancelled = FakeScreenTurn()
        val goal = RunningGoal(1, 3, emptyMap(), emptyList(), listener)
        // The round's job is cancelled while the round acts, holding the turn, as "stop" cancels it during a model call.
        val cancelling = environment(listSms, finish(1)).apply { afterCall = { throw TaskCancellation(EndReason.CANCELLED) } }
        runner(cancelling, cancelled).run(this, goal, plan(record()), FakeOwner())
        advanceUntilIdle()
        assertEquals(3, listener.ends.size)
        assertEquals(EndReason.CANCELLED, listener.ends.last().report.end)
        assertTrue(cancelled.takes > 0)
        assertFalse(cancelled.holds)
    }

    @Test
    fun theCallBackstopsSentenceLeadsTheAnswer() = runTest {
        val environment = environment(ScriptedModel.call("say", null, null, null, "text" to "On it."), listSms, finish(1))
        val goal = RunningGoal(1, 1, emptyMap(), emptyList(), listener)
        val owner = FakeOwner()
        owner.onSay = {
            goal.cancel(EndReason.CANCELLED, ConversationTexts.CALL_STARTED)
            yield()
        }

        runner(environment).run(this, goal, plan(record()), owner)
        advanceUntilIdle()

        val end = listener.ends.single()
        assertEquals(EndReason.CANCELLED, end.report.end)
        assertEquals("${ConversationTexts.CALL_STARTED} ${ConversationTexts.NOTHING_CHANGED}", end.answer)
        assertEquals(end.answer, end.reply)
        assertEquals(1, environment.released)
    }

    // A cancel while no round runs (a held goal) cancels nothing, so its sentence must not reach the next round's end.
    @Test
    fun aStopSentenceWhileNoRoundRunsIsNotTheNextRoundsAnswer() = runTest {
        val environment = environment(listSms, finish(1))
        val goal = RunningGoal(1, 1, emptyMap(), emptyList(), listener)
        goal.cancel(EndReason.CANCELLED, ConversationTexts.CALL_STARTED)

        runner(environment).run(this, goal, plan(record()), FakeOwner())
        advanceUntilIdle()

        val end = listener.ends.single()
        assertEquals(EndReason.FINISHED, end.report.end)
        assertEquals(FakeGoalEnvironment.sentenceOf(end.report), end.answer)
    }

    // A refused round ends the goal for good, so the steering that waited for it is named as too late, as at every such end.
    @Test
    fun aRefusedStartNamesTheSteeringThatWaited() = runTest {
        val environment = environment(listSms)
        environment.refusal = RoundSetup.Refused("Set up a model in Settings first.", EndReason.NO_PROFILE)
        val goal = RunningGoal(1, 1, emptyMap(), listOf(SteeringMessage.of("Make it 7:30", InputChannel.VOICE)), listener)

        runner(environment).run(this, goal, plan(record()), FakeOwner())
        advanceUntilIdle()

        val end = listener.ends.single()
        assertEquals(EndReason.NO_PROFILE, end.report.end)
        assertEquals("Set up a model in Settings first." + ConversationTexts.tooLate(listOf(SteeringMessage.of("Make it 7:30", InputChannel.VOICE))), end.answer)
        assertEquals(end.answer, end.reply)
        assertTrue(goal.inbox.pending().isEmpty())
    }

    // A release that throws a CancellationException of its own (a timeout in it) must not skip the end.
    @Test
    fun aCancelledReleaseStillEndsTheRound() = runTest {
        val environment = environment(listSms, finish(1))
        val cancelling = Changed(environment) { ready -> ready.with(release = { throw CancellationException("The release timed out") }) }

        runner(cancelling).run(this, RunningGoal(1, 1, emptyMap(), emptyList(), listener), plan(record()), FakeOwner())
        advanceUntilIdle()

        val end = listener.ends.single()
        assertEquals(EndReason.FINISHED, end.report.end)
        assertEquals(FakeGoalEnvironment.sentenceOf(end.report), end.answer)
    }

    @Test
    fun saveThatAsUsesTheNewestFinishedGoalForTenMinutes() = runTest {
        // A goal that did something on the screen leaves its steps for "save that as".
        val start = ScreenBuilder.screen("dev.kestrel.testapp", "Kestrel fixtures") { button("Open", 300) }
        val opened = ScreenBuilder.screen("dev.kestrel.testapp", "Kestrel fixtures") { text("Opened", 300) }
        val tap = ScriptedModel.call("tap", "Open", null, null, "reason" to "open it", "category" to "reading")
        val done = ScriptedModel.call("finish", null, null, null, "summary" to "Opened.", "evidence" to listOf(mapOf("target" to mapOf("label" to "Opened"))))
        val environment = FakeGoalEnvironment(listOf(tap, done), first = start)
        environment.screen.onTap("Open", opened)
        val runner = runner(environment)
        val owner = FakeOwner()
        assertEquals(ConversationTexts.NOTHING_TO_SAVE, runner.saveRoutine("evening", owner))

        runner.run(this, RunningGoal(1, 1, emptyMap(), emptyList(), listener), plan(record()), owner)
        advanceUntilIdle()
        assertEquals(EndReason.FINISHED, listener.ends.single().report.end)
        runner.saveRoutine("evening", owner)
        assertEquals(RoundPurpose.ROUTINE, environment.starts.last().purpose)

        advanceTimeBy(GoalRunner.RECORDING_MILLIS + 1)
        val starts = environment.starts.size
        assertEquals(ConversationTexts.NOTHING_TO_SAVE, runner.saveRoutine("evening", owner))
        assertEquals(starts, environment.starts.size)
    }

    @Test
    fun theSteeringInboxDrainsInOrderAndTellsTheGoal() {
        var changes = 0
        val inbox = SteeringInbox(listOf(SteeringMessage.of("first", InputChannel.TEXT))) { changes++ }

        inbox.add(SteeringMessage.of("second", InputChannel.VOICE))

        assertEquals(listOf("first", "second"), inbox.pending().map { it.recorded })
        assertEquals(listOf(Steer("first", SteerChannel.TYPED), Steer("second", SteerChannel.SPOKEN)), inbox.drain().map { it.steer })
        assertTrue(inbox.drain().isEmpty())
        assertEquals(2, changes)
        assertNull(inbox.pending().firstOrNull())
    }
}
