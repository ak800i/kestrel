package dev.kestrel.agent.loop

import dev.kestrel.agent.EndReason
import dev.kestrel.agent.Harness
import dev.kestrel.agent.Screens
import dev.kestrel.agent.TaskRequest
import dev.kestrel.agent.goal.GoalLimits
import dev.kestrel.agent.port.Answer
import dev.kestrel.agent.port.AnswerKind
import dev.kestrel.agent.port.Gate
import dev.kestrel.agent.testing.FakeClock
import dev.kestrel.agent.testing.ScreenBuilder
import dev.kestrel.agent.testing.ScriptedModel
import dev.kestrel.agent.testing.ScriptedModel.Companion.call
import dev.kestrel.providers.chat.ReasoningLevel
import dev.kestrel.providers.chat.ToolChoice
import dev.kestrel.providers.profiles.ModelRole
import dev.kestrel.screen.model.ScreenSnapshot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A round's limits with "Continue?" (design 7.4 of sub-project 7), and spec 11.7's escalation (design D7). */
class LimitsTest {
    private fun twoScreens(): Pair<ScreenSnapshot, ScreenSnapshot> {
        val a = ScreenBuilder.screen("com.example.app") { text("Page A", 100, viewId = "toolbar_title"); button("Go to B", 600) }
        val b = ScreenBuilder.screen("com.example.app") { text("Page B", 100, viewId = "toolbar_title"); button("Go to A", 900) }
        return a to b
    }

    // Taps back and forth between the two pages: each of them is progress.
    private fun goingOn(count: Int, credits: Double? = null): List<JsonObject> = (1..count).map {
        if (it % 2 == 1) {
            call("tap", "Go to B", null, credits, "reason" to "on", "category" to "reading")
        } else {
            call("tap", "Go to A", null, credits, "reason" to "back", "category" to "reading")
        }
    }

    private fun harness(replies: List<JsonObject>): Harness {
        val (a, b) = twoScreens()
        return Harness(a, replies).apply {
            screen.onTap("Go to B", b)
            screen.onTap("Go to A", a)
        }
    }

    private fun keepGoing(limits: GoalLimits) = TaskRequest(Harness.ID, "Keep going", limits = limits)

    @Test
    fun atTheReplyLimitKestrelAsksAndANoStopsTheGoal() = runTest {
        val harness = harness(goingOn(10))
        harness.owner.answers += Answer.No

        val report = harness.run(keepGoing(GoalLimits(replies = 4)))

        assertEquals(EndReason.STOPPED_AT_LIMIT, report.end)
        assertEquals(4, harness.model.requests.size)
        val (question, kind) = harness.owner.asked.single()
        assertEquals(AnswerKind.LIMIT, kind)
        assertTrue(question, question.startsWith("I've used 4 model replies on this. "))
        assertTrue(question, question.endsWith(" Continue?"))
    }

    @Test
    fun aYesGivesTheSameAgainAndNoAnswerPausesTheGoal() = runTest {
        val harness = harness(goingOn(10))
        harness.owner.answers += Answer.Yes

        val report = harness.run(keepGoing(GoalLimits(replies = 3)))

        assertEquals(EndReason.PAUSED_AT_LIMIT, report.end)
        assertEquals(6, harness.model.requests.size)
        val asked = harness.owner.asked.map { it.first }
        assertEquals(2, asked.size)
        assertTrue(asked[0], asked[0].startsWith("I've used 3 model replies on this. "))
        assertTrue(asked[1], asked[1].startsWith("I've used 6 model replies on this. "))
    }

    @Test
    fun workingTimeLeavesOutTheOwnersWaits() = runTest {
        val clock = FakeClock()
        val limits = Limits(clock, countsCredits = true, round = GoalLimits(workMinutes = 5))
        clock.elapsed = 60_000
        limits.waiting { clock.elapsed += 120_000 }
        clock.elapsed += 30_000

        assertEquals(90_000, limits.workMillis)
        assertNull(limits.reached(0))
        clock.elapsed += 210_000
        assertEquals(Limit.TIME, limits.reached(0))
    }

    @Test
    fun eachLimitIsReachedInItsOrderAndARoundGivesTheSameAgain() {
        val limits = Limits(FakeClock(), countsCredits = true, round = GoalLimits(replies = 10, workMinutes = 5, credits = 10))
        limits.spent(9.5)
        assertNull(limits.reached(9))
        limits.spent(3.2)
        assertEquals(Limit.CREDITS, limits.reached(9))
        assertEquals(Limit.REPLIES, limits.reached(10))

        limits.nextRound(10)

        assertNull(limits.reached(19))
        assertEquals(Limit.REPLIES, limits.reached(20))
        limits.spent(10.0)
        assertEquals(Limit.CREDITS, limits.reached(19))
        val plan = Limits(FakeClock(), countsCredits = false, round = GoalLimits(credits = 10)).apply { spent(50.0) }
        assertNull(plan.reached(0))
    }

    @Test
    fun aNoAtTheCreditLimitStopsTheGoal() = runTest {
        val harness = harness(goingOn(3, credits = 6.0))
        harness.owner.answers += Answer.No

        val report = harness.run(keepGoing(GoalLimits(credits = 10)))

        assertEquals(EndReason.STOPPED_AT_LIMIT, report.end)
        assertTrue(harness.owner.asked.single().first.startsWith("This has used 12 AI Credits. "))
        assertEquals(2, harness.model.requests.size)
    }

    @Test
    fun aResumeDuringTheLimitsQuestionIsObservedBeforeThatTurnsModelCall() = runTest {
        val harness = harness(goingOn(2, credits = 6.0) + call("fail", null, null, null, "reason" to "Stop."))
        // The gates of the first two turns and their taps, then the gate of the limit's question.
        harness.owner.gates += listOf(Gate.GO, Gate.GO, Gate.GO, Gate.GO, Gate.GO, Gate.RESUMED)
        harness.owner.answers += Answer.Yes

        harness.run(keepGoing(GoalLimits(credits = 10)))

        assertTrue(harness.owner.asked.single().first.startsWith("This has used 12 AI Credits. "))
        assertEquals(3, harness.model.requests.size)
        assertTrue(harness.requestText(2).contains(TaskRun.RESUMED))
    }

    @Test
    fun twoInvalidCallsMoveTheTaskToTheVersatileModelWithItsReasoningLevel() = runTest {
        val text = buildJsonObject { put("text", "I will tap it.") }
        val harness = Harness(Screens.chats(), listOf(text, text, call("fail", null, null, null, "reason" to "Stop.")))

        harness.run("Open Ana's chat")

        assertEquals(listOf(ModelRole.LIGHTWEIGHT, ModelRole.LIGHTWEIGHT, ModelRole.VERSATILE), harness.model.roles)
        val versatile = harness.model.requests[2]
        assertEquals(ReasoningLevel.LOW, versatile.reasoning)
        assertEquals(ToolChoice.AUTO, versatile.toolChoice)
        assertNull(harness.model.requests[0].reasoning)
        assertEquals(ToolChoice.REQUIRED, harness.model.requests[0].toolChoice)
    }

    @Test
    fun afterASwitchALocalOllamaTaskUsesTheVersatileModelsBudget() = runTest {
        val text = buildJsonObject { put("text", "I will tap it.") }
        val info = ScriptedModel.INFO.copy(
            localOllama = true,
            lightweight = ScriptedModel.INFO.lightweight.copy(contextWindow = 32_768),
            versatile = ScriptedModel.INFO.versatile.copy(contextWindow = 2_000),
        )
        val harness = Harness(Screens.chats(), listOf(text, text, call("fail", null, null, null, "reason" to "Stop.")), model = info)

        val report = harness.run("Open Ana's chat")

        assertEquals(EndReason.CONTEXT_TOO_SMALL, report.end)
        assertEquals(listOf(ModelRole.LIGHTWEIGHT, ModelRole.LIGHTWEIGHT), harness.model.roles)
    }

    @Test
    fun threeStepsWithoutProgressSwitchModelsAndThenAskTheOwner() = runTest {
        val stay = call("tap", "Hi", null, null, "reason" to "look", "category" to "reading")
        val harness = Harness(Screens.chat("Ana Petrović", "Hi"), List(6) { stay })
        harness.owner.answers += Answer.No

        val report = harness.run("Do something")

        assertEquals(EndReason.STUCK, report.end)
        assertEquals(List(3) { ModelRole.LIGHTWEIGHT } + List(3) { ModelRole.VERSATILE }, harness.model.roles)
        assertEquals(Limits.STUCK_QUESTION, harness.owner.asked.single().first)
    }

    @Test
    fun threeInvalidCallsOnTheVersatileModelEndTheTask() = runTest {
        val text = buildJsonObject { put("text", "Hmm.") }
        val harness = Harness(Screens.chats(), List(5) { text })

        assertEquals(EndReason.INVALID_CALLS, harness.run("Open Ana's chat").end)
        assertEquals(5, harness.model.requests.size)
    }

    @Test
    fun escalationResetsItsCountsAndStaysWhenTheVersatileModelIsRefused() {
        val progress = Progress()
        val escalation = Escalation(ScriptedModel.INFO)
        assertEquals(Escalate.NOTHING, escalation.invalid(progress))
        assertEquals(Escalate.SWITCHED, escalation.invalid(progress))
        assertEquals(ModelRole.VERSATILE, escalation.role)

        val refused = ScriptedModel.INFO.copy(versatile = ScriptedModel.INFO.versatile.copy(unusable = "Claude Opus 5 and Sonnet 5 work on Vertex only in the global, us and eu locations. Change the profile's location or model."))
        val stays = Escalation(refused)
        repeat(3) { progress.record("tap", Outcome.NothingChanged) }
        assertEquals(Escalate.END_STUCK, stays.afterStep(progress))
        assertEquals(ModelRole.LIGHTWEIGHT, stays.role)
    }
}
