package dev.kestrel.agent.loop

import dev.kestrel.agent.EndReason
import dev.kestrel.agent.Harness
import dev.kestrel.agent.Screens
import dev.kestrel.agent.port.Answer
import dev.kestrel.agent.port.Gate
import dev.kestrel.agent.testing.FakeClock
import dev.kestrel.agent.testing.ScreenBuilder
import dev.kestrel.agent.testing.ScriptedModel
import dev.kestrel.agent.testing.ScriptedModel.Companion.call
import dev.kestrel.providers.chat.ReasoningLevel
import dev.kestrel.providers.chat.ToolChoice
import dev.kestrel.providers.profiles.ModelRole
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LimitsTest {
    private fun twoScreens(): Pair<dev.kestrel.screen.model.ScreenSnapshot, dev.kestrel.screen.model.ScreenSnapshot> {
        val a = ScreenBuilder.screen("com.example.app") { text("Page A", 100, viewId = "toolbar_title"); button("Go to B", 600) }
        val b = ScreenBuilder.screen("com.example.app") { text("Page B", 100, viewId = "toolbar_title"); button("Go to A", 900) }
        return a to b
    }

    @Test
    fun theThirtyFirstReplyIsNeverRequested() = runTest {
        val (a, b) = twoScreens()
        val taps = (1..40).map { if (it % 2 == 1) call("tap", "Go to B", null, null, "reason" to "on", "category" to "reading") else call("tap", "Go to A", null, null, "reason" to "back", "category" to "reading") }
        val harness = Harness(a, taps)
        harness.screen.onTap("Go to B", b)
        harness.screen.onTap("Go to A", a)

        val report = harness.run("Keep going")

        assertEquals(EndReason.STEPS, report.end)
        assertEquals(Limits.MAX_REPLIES, harness.model.requests.size)
        assertEquals(30, report.steps)
    }

    @Test
    fun workingTimeLeavesOutTheOwnersWaits() = runTest {
        val clock = FakeClock()
        val limits = Limits(clock, countsCredits = true)
        clock.elapsed = 60_000
        limits.waiting { clock.elapsed += 120_000 }
        clock.elapsed += 30_000

        assertEquals(90_000, limits.workMillis)
        clock.elapsed += 210_000
        assertTrue(limits.timeUsed())
    }

    @Test
    fun theCostQuestionComesAtEachTenCreditsWithTheRealTotal() {
        val limits = Limits(FakeClock(), countsCredits = true)
        limits.spent(9.5)
        assertNull(limits.creditQuestion())
        limits.spent(3.2)
        assertEquals("This task has used 12 credits. Continue?", limits.creditQuestion())
        limits.allowMoreCredits()
        assertNull(limits.creditQuestion())
        limits.spent(10.0)
        assertEquals("This task has used 22 credits. Continue?", limits.creditQuestion())
        val plan = Limits(FakeClock(), countsCredits = false).apply { spent(50.0) }
        assertNull(plan.creditQuestion())
    }

    @Test
    fun aNoToTheCostQuestionEndsTheTask() = runTest {
        val (a, b) = twoScreens()
        val harness = Harness(a, listOf(
            call("tap", "Go to B", null, 6.0, "reason" to "on", "category" to "reading"),
            call("tap", "Go to A", null, 6.0, "reason" to "back", "category" to "reading"),
            call("tap", "Go to B", null, 6.0, "reason" to "on", "category" to "reading"),
        ))
        harness.screen.onTap("Go to B", b)
        harness.screen.onTap("Go to A", a)
        harness.owner.answers += Answer.No

        val report = harness.run("Keep going")

        assertEquals(EndReason.COST, report.end)
        assertEquals("This task has used 12 credits. Continue?", harness.owner.asked.single().first)
        assertEquals(2, harness.model.requests.size)
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
    fun aResumeDuringTheCostQuestionIsObservedBeforeThatTurnsModelCall() = runTest {
        val (a, b) = twoScreens()
        val harness = Harness(a, listOf(
            call("tap", "Go to B", null, 6.0, "reason" to "on", "category" to "reading"),
            call("tap", "Go to A", null, 6.0, "reason" to "back", "category" to "reading"),
            call("fail", null, null, null, "reason" to "Stop."),
        ))
        harness.screen.onTap("Go to B", b)
        harness.screen.onTap("Go to A", a)
        // The gates of the first two turns and their taps, then the gate of the cost question.
        harness.owner.gates += listOf(Gate.GO, Gate.GO, Gate.GO, Gate.GO, Gate.GO, Gate.RESUMED)
        harness.owner.answers += Answer.Yes

        harness.run("Keep going")

        assertEquals("This task has used 12 credits. Continue?", harness.owner.asked.single().first)
        assertEquals(3, harness.model.requests.size)
        assertTrue(harness.requestText(2).contains(TaskRun.RESUMED))
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
