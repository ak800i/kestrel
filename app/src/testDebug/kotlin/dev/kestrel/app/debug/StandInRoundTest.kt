package dev.kestrel.app.debug

import dev.kestrel.agent.Agent
import dev.kestrel.agent.EndReason
import dev.kestrel.agent.TaskReport
import dev.kestrel.agent.TaskRequest
import dev.kestrel.agent.goal.NoGoal
import dev.kestrel.agent.goal.NoHistory
import dev.kestrel.agent.port.HandOverEnd
import dev.kestrel.agent.port.ScreenAction
import dev.kestrel.agent.testing.FakeScreen
import dev.kestrel.agent.testing.FakeOwner
import dev.kestrel.conversation.goal.RoundPurpose
import dev.kestrel.conversation.goal.RoundSetup
import dev.kestrel.conversation.goal.RoundStart
import dev.kestrel.safety.HandOverKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The A71 session's stand-in round (`voice-task`; design 14.4 of sub-project 7). */
@OptIn(ExperimentalCoroutinesApi::class)
class StandInRoundTest {
    @Test
    fun aStandInRoundTakesItsSecondsAndItsWholeReplyOfAnyLengthIsTheGoalsFinalWords() = runTest {
        // As long as the A71 session's story, far beyond what a model may give as its summary or a failure's reason.
        val reply = "Once upon a time a kestrel hovered over a quiet field. ".repeat(9).trimEnd().take(STORY)
        assertEquals(STORY, reply.length)
        val standIn = StandInRound(seconds = 3, reply = reply)

        val setup = standIn.prepare(RoundStart(1, emptyMap(), FakeOwner(), NoGoal, NoHistory, RoundPurpose.GOAL)) as RoundSetup.Ready
        var report: TaskReport? = null
        Agent(setup.ports, setup.settings).run(TaskRequest(setup.taskId, "Read me the news")) { report = it }

        val ended = report!!
        assertEquals(EndReason.FINISHED, ended.end)
        assertTrue("The round ended after ${currentTime} ms", currentTime >= 3_000)
        assertEquals(reply, standIn.sentence(ended))
        // Any other end keeps Kestrel's own words for it.
        assertEquals("Cancelled. I didn't change anything.", standIn.sentence(TaskReport(EndReason.CANCELLED)))
    }

    // Plan 7F's Task 14, Step 8a: with a hand-over, the round first hands the owner a payment step, which opens no page,
    // touches nothing on the phone, and after his Continue the round finishes with the reply as without it.
    @Test
    fun aStandInRoundWithAHandOverHandsTheOwnerAPaymentStepAndFinishesAfterHisContinue() = runTest {
        val standIn = StandInRound(seconds = 2, reply = "Done.", handOver = true)
        val owner = FakeOwner().apply { handOverEnds += HandOverEnd.CONTINUED }

        val setup = standIn.prepare(RoundStart(1, emptyMap(), owner, NoGoal, NoHistory, RoundPurpose.GOAL)) as RoundSetup.Ready
        var report: TaskReport? = null
        Agent(setup.ports, setup.settings).run(TaskRequest(setup.taskId, "Pay the bill")) { report = it }

        assertEquals(listOf(HandOverKind.PAYMENT), owner.handOvers.map { it.kind })
        assertEquals(EndReason.FINISHED, report!!.end)
        assertEquals(emptyList<ScreenAction>(), (setup.ports.screen as FakeScreen).actions)
        assertEquals("Done.", standIn.sentence(report!!))
    }

    // voice-task's answer names the payment step only when the round hands one over, and never the reply's words.
    @Test
    fun voiceTasksAnswerSaysWhetherTheRoundHandsOverAPaymentStep() {
        assertEquals(
            "each goal's round now takes 2 s and then answers with a reply of 5 characters, until voice-task -Clear or the " +
                "end of Kestrel's process",
            StandInRound.answer(2, 5, handOver = false),
        )
        assertEquals(
            "each goal's round now takes 2 s, first hands the owner a payment step, and then answers with a reply of 5 " +
                "characters, until voice-task -Clear or the end of Kestrel's process",
            StandInRound.answer(2, 5, handOver = true),
        )
    }

    private companion object {
        const val STORY = 478
    }
}
