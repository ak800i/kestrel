package dev.kestrel.app.agent

import dev.kestrel.agent.EndReason
import dev.kestrel.agent.Interruption
import dev.kestrel.agent.TaskReport
import dev.kestrel.agent.goal.Checklist
import dev.kestrel.agent.goal.ChecklistItem
import dev.kestrel.agent.goal.ItemState
import dev.kestrel.providers.http.ProviderError
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentTextsTest {
    @Test
    fun aFinishedTaskSaysItsSendsAndThenTheSummary() {
        val report = TaskReport(EndReason.FINISHED, summary = "The message is in My Notes.", sends = listOf("Sent to My Notes on Viber."))

        assertEquals("Sent to My Notes on Viber. The message is in My Notes.", AgentTexts.sentence(report, "Copilot"))
        assertEquals("Done.", AgentTexts.sentence(TaskReport(EndReason.FINISHED), "Copilot"))
    }

    @Test
    fun anyOtherEndSaysItsReasonAndWhatTheTaskDid() {
        val stopped = TaskReport(EndReason.DECLINED, ledger = listOf("opened Viber", "typed the message", "scrolled down"))
        val failed = TaskReport(EndReason.PROVIDER, error = ProviderError.Timeout)

        assertEquals("I stopped because you said no. I opened Viber, typed the message and scrolled down.", AgentTexts.sentence(stopped, "Copilot"))
        assertEquals("Copilot did not answer in time. I didn't change anything.", AgentTexts.sentence(failed, "Copilot"))
        assertEquals(
            "Bank Intesa is on your list of agent-free apps, so I won't act in it. I didn't change anything.",
            AgentTexts.sentence(TaskReport(EndReason.AGENT_FREE, detail = "Bank Intesa"), "Copilot"),
        )
    }

    @Test
    fun aRoundThatCannotStartSaysWhy() {
        assertEquals("I couldn't start the task: ${AgentTexts.NO_PROFILE}", AgentTexts.couldNotStart(AgentTexts.NO_PROFILE))
    }

    // Plan 7A: a round's limit says what is done and left from the checklist, and what the task did without one.
    @Test
    fun aLimitsEndSaysWhatIsDoneAndLeftFromTheChecklist() {
        val checklist = Checklist(listOf(ChecklistItem("c1", "Open Viber", ItemState.DONE, step = 2), ChecklistItem("c2", "Reply to Mila", ItemState.TODO)))

        assertEquals(
            "I stopped at the limit, as you said. Done: 1 of 2 steps. Left: Reply to Mila.",
            AgentTexts.sentence(TaskReport(EndReason.STOPPED_AT_LIMIT, ledger = listOf("opened Viber"), checklist = checklist), "Copilot"),
        )
        assertEquals(
            "I stopped at the limit because you didn't answer. I opened Viber.",
            AgentTexts.sentence(TaskReport(EndReason.PAUSED_AT_LIMIT, ledger = listOf("opened Viber")), "Copilot"),
        )
    }

    // Plan 7A: an interruption says that Kestrel stopped, and why when Kestrel's own screen stayed in front.
    @Test
    fun anInterruptionSaysThatKestrelStoppedAndWhy() {
        assertEquals(
            "I stopped because Kestrel's own screen stayed in front. I opened Viber.",
            AgentTexts.sentence(TaskReport(EndReason.INTERRUPTED, interruption = Interruption.KESTREL_SCREEN, ledger = listOf("opened Viber")), "Copilot"),
        )
        assertEquals(
            "I stopped. I didn't change anything.",
            AgentTexts.sentence(TaskReport(EndReason.INTERRUPTED, interruption = Interruption.TOUCH), "Copilot"),
        )
    }
}
