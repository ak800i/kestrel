package dev.kestrel.app.debug

import dev.kestrel.agent.Agent
import dev.kestrel.agent.EndReason
import dev.kestrel.agent.TaskReport
import dev.kestrel.agent.TaskRequest
import dev.kestrel.agent.goal.NoGoal
import dev.kestrel.agent.goal.NoHistory
import dev.kestrel.agent.testing.FakeOwner
import dev.kestrel.conversation.goal.RoundPurpose
import dev.kestrel.conversation.goal.RoundSetup
import dev.kestrel.conversation.goal.RoundStart
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

    private companion object {
        const val STORY = 478
    }
}
