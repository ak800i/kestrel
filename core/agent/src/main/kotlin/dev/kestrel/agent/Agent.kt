package dev.kestrel.agent

import dev.kestrel.agent.loop.TaskRun
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Kestrel's agent (design D1; spec 11): [run] carries out one task to its end and hands its report to `report` exactly
 * once on every path (review A M14). A cancellation (a touch, "stop", a new task after "stop", the end of a long pause)
 * arrives as a CancellationException: the agent then builds its report from what it had done inside NonCancellable,
 * hides the numbers that it may have shown, calls `report`, and rethrows (D24, D32 items 1 and 4). The task's vault is
 * cleared in every case (spec 12.6).
 */
class Agent(private val ports: AgentPorts, private val settings: AgentSettings) {
    suspend fun run(task: TaskRequest, report: (TaskReport) -> Unit) {
        val run = TaskRun(ports, settings, task)
        var reported = false
        val once: (TaskReport) -> Unit = {
            if (!reported) {
                reported = true
                report(it)
            }
        }
        try {
            val result = run.run()
            withContext(NonCancellable) { end(run, result, once) }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { end(run, run.cancelled((e as? TaskCancellation)?.reason ?: EndReason.CANCELLED), once) }
            throw e
        } catch (e: Exception) {
            withContext(NonCancellable) { end(run, run.cancelled(EndReason.KESTREL_ERROR), once) }
            throw e
        } finally {
            run.close()
        }
    }

    private suspend fun end(run: TaskRun, result: TaskReport, report: (TaskReport) -> Unit) {
        try {
            ports.screen.hideBadges()
        } catch (e: Exception) {
            // The report matters more than the numbers' overlay, which the screen layer removes with the task anyway.
        }
        run.logEnd(result)
        report(result)
    }

    override fun toString(): String = "Agent"
}
