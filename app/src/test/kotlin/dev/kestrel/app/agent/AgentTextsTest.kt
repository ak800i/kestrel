package dev.kestrel.app.agent

import dev.kestrel.agent.EndReason
import dev.kestrel.agent.TaskReport
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
        val stopped = TaskReport(EndReason.STEPS, ledger = listOf("opened Viber", "typed the message", "scrolled down"))
        val failed = TaskReport(EndReason.PROVIDER, error = ProviderError.Timeout)

        assertEquals("I stopped after 30 steps. I opened Viber, typed the message and scrolled down.", AgentTexts.sentence(stopped, "Copilot"))
        assertEquals("Copilot did not answer in time. I didn't change anything.", AgentTexts.sentence(failed, "Copilot"))
        assertEquals(
            "Bank Intesa is on your list of agent-free apps, so I won't act in it. I didn't change anything.",
            AgentTexts.sentence(TaskReport(EndReason.AGENT_FREE, detail = "Bank Intesa"), "Copilot"),
        )
    }

    @Test
    fun aReplacedTasksReportForTheBarStartsWithCancelled() {
        assertEquals("Cancelled: I opened Viber.", AgentTexts.replaced(TaskReport(EndReason.CANCELLED, ledger = listOf("opened Viber"))))
        assertEquals("I couldn't start the task: ${AgentTexts.NO_PROFILE}", AgentTexts.couldNotStart(AgentTexts.NO_PROFILE))
    }
}
