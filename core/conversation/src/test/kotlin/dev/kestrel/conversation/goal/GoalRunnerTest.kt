package dev.kestrel.conversation.goal

import dev.kestrel.agent.EndReason
import dev.kestrel.agent.Interruption
import dev.kestrel.agent.TaskReport
import dev.kestrel.agent.goal.Checklist
import dev.kestrel.agent.goal.ChecklistItem
import dev.kestrel.agent.goal.GoalLimits
import dev.kestrel.agent.goal.ItemState
import dev.kestrel.agent.goal.Steer
import dev.kestrel.agent.goal.SteerChannel
import dev.kestrel.agent.goal.TraceEntry
import dev.kestrel.agent.port.DataField
import dev.kestrel.agent.port.DataLine
import dev.kestrel.agent.port.DataResult
import dev.kestrel.agent.testing.FakeOwner
import dev.kestrel.agent.testing.ScreenBuilder
import dev.kestrel.agent.testing.ScriptedModel
import dev.kestrel.conversation.ConversationTexts
import dev.kestrel.conversation.model.CommandRecord
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.store.ArchivedConversationStore
import dev.kestrel.conversation.store.HistorySearch
import dev.kestrel.conversation.store.RecordRef
import dev.kestrel.conversation.testing.FakeGoalEnvironment
import dev.kestrel.conversation.testing.ManualConversationClock
import dev.kestrel.conversation.testing.MemoryArchive
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.Part
import dev.kestrel.safety.Category
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
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

    private fun plan(record: CommandRecord, testMode: Boolean = false) =
        RoundPlan(record, emptyList(), record.request, null, testMode, null, GoalLimits(), emptyMap(), RecordRef(id, record.number), ManualConversationClock.BELGRADE)

    private fun TestScope.runner(environment: FakeGoalEnvironment): GoalRunner {
        val clock = ManualConversationClock({ testScheduler.currentTime })
        return GoalRunner(environment, ScreenTurn { }, HistorySearch(ArchivedConversationStore(MemoryArchive(), backgroundScope) {}, { 30 }, clock), clock) { _, error -> throw error }
    }

    private fun environment(vararg replies: kotlinx.serialization.json.JsonObject) = FakeGoalEnvironment(replies.toList()).apply { data.answer("list_sms", sms) }

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
        val runner = GoalRunner(failing, ScreenTurn { }, HistorySearch(ArchivedConversationStore(MemoryArchive(), backgroundScope) {}, { 30 }, clock), clock) { what, _ -> failures += what }

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
        goal.steer("Also check Ana's", InputChannel.TEXT)

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
        environment.afterCall = { calls -> if (calls == 2) goal.steer("Make it 7:30", InputChannel.VOICE) }

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
            goal.steer("Also Ana", InputChannel.TEXT)
            goal.interrupt(Interruption.TOUCH)
            yield()
        }

        runner(environment).run(this, goal, plan(record()), owner)
        advanceUntilIdle()

        val end = listener.ends.single()
        assertEquals(EndReason.INTERRUPTED, end.report.end)
        assertEquals(Interruption.TOUCH, end.report.interruption)
        assertEquals(FakeGoalEnvironment.sentenceOf(end.report), end.reply)
        assertEquals(listOf("Also Ana"), goal.inbox.pending().map { it.text })
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

        val end = runner(environment()).endHeld(3, held, listOf(Steer("Make it 7:30", SteerChannel.SPOKEN)), EndReason.PAUSED_TOO_LONG)

        assertEquals(3, end.command)
        assertEquals(EndReason.PAUSED_TOO_LONG, end.report.end)
        assertEquals(listOf("opened Viber"), end.report.ledger)
        assertEquals(checklist, end.report.checklist)
        assertEquals(FakeGoalEnvironment.sentenceOf(end.report) + ConversationTexts.tooLate(listOf("Make it 7:30")), end.answer)
    }

    @Test
    fun aRoutineOfferSaysTheReportFirstAndTheGoalKeepsItAsItsAnswer() = runTest {
        val start = ScreenBuilder.screen("dev.kestrel.testapp", "Kestrel fixtures") { button("Open", 300) }
        val opened = ScreenBuilder.screen("dev.kestrel.testapp", "Kestrel fixtures") { text("Opened", 300) }
        val tap = ScriptedModel.call("tap", "Open", null, null, "reason" to "open it", "category" to "reading")
        val done = ScriptedModel.call(
            "finish", null, null, null,
            "summary" to "Opened.", "evidence" to listOf(mapOf("target" to mapOf("label" to "Opened"))), "signature" to "fixtures.open.button",
        )
        val environment = FakeGoalEnvironment(listOf(tap, done), first = start).apply { offersRoutines = true }
        environment.screen.onTap("Open", opened)
        // The second finish with this signature offers a routine (design D23 of sub-project 5); the owner gives no answer.
        environment.memory.signatures["fixtures.open.button"] = 1
        val owner = FakeOwner()

        runner(environment).run(this, RunningGoal(1, 1, emptyMap(), emptyList(), listener), plan(record()), owner)
        advanceUntilIdle()

        val end = listener.ends.single()
        assertTrue(end.report.offerRoutine)
        assertEquals(FakeGoalEnvironment.sentenceOf(end.report), end.answer)
        assertNull(end.reply)
        assertEquals(end.answer, owner.said.last())
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
        val inbox = SteeringInbox(listOf(Steer("first", SteerChannel.TYPED))) { changes++ }

        inbox.add(Steer("second", SteerChannel.SPOKEN))

        assertEquals(listOf("first", "second"), inbox.pending().map { it.text })
        assertEquals(listOf("first", "second"), inbox.drain().map { it.text })
        assertTrue(inbox.drain().isEmpty())
        assertEquals(2, changes)
        assertNull(inbox.pending().firstOrNull())
    }
}
