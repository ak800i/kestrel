package dev.kestrel.agent.loop

import dev.kestrel.agent.goal.HistoryQuery
import dev.kestrel.agent.port.TaskLog
import dev.kestrel.agent.tools.AgentCall
import kotlinx.coroutines.CancellationException

/**
 * `search_conversations` (design 9.4 of sub-project 7): it acts on nothing and needs no safety check, because its result
 * is data. This run's model reads it, and the run traces it as the command's data note, which later commands read as data
 * too, in their records block and their own searches. As a data tool's, a result that differs from the tool's earlier
 * results in the run is progress, and a result with matches is a data step that `finish` may cite. A failure of the
 * archive is the step's result, never the end of the task; it is logged by its class.
 */
internal class HistorySteps(private val run: TaskRun) {
    suspend fun search(call: AgentCall.SearchConversations): Done {
        val detail = run.redactor.redact(call.query)
        val result = try {
            run.ports.history.search(HistoryQuery(call.query, call.from, call.to))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            run.log(TaskLog.Kind.ERROR, "history: ${e.javaClass.name}")
            return Done(call.tool, Outcome.Failed(UNAVAILABLE), detail = detail)
        }
        val shown = HistoryText.of(result, run.ports.clock.now().zone)
        val outcome = if (shown.count == 0) Outcome.DataEmpty else Outcome.DataOk(shown.count)
        return Done(call.tool, outcome, detail = detail, data = shown.text)
    }

    override fun toString(): String = "HistorySteps"

    private companion object {
        const val UNAVAILABLE = "Kestrel could not read its past conversations"
    }
}
