package dev.kestrel.app.agent

import dev.kestrel.agent.port.TaskLog
import dev.kestrel.privacy.logs.ActivityEvent
import dev.kestrel.privacy.logs.ActivityKind
import kotlinx.coroutines.CancellationException

/**
 * One task's entries in the encrypted activity log (design D25 and D32 item 46 of sub-project 5), with the task's ID
 * [taskId]. The agent writes them already redacted. A failed write goes to [report] by its classes and never ends the
 * task.
 */
internal class LogTaskLog(
    private val taskId: String,
    private val append: suspend (ActivityEvent) -> Unit,
    private val report: (String, Throwable) -> Unit,
) : TaskLog {
    override suspend fun write(kind: TaskLog.Kind, text: String) {
        try {
            append(ActivityEvent(kindOf(kind), text, taskId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report("Writing a task's log entry", e)
        }
    }

    override fun toString(): String = "LogTaskLog(task=$taskId)"

    companion object {
        fun kindOf(kind: TaskLog.Kind): ActivityKind = when (kind) {
            TaskLog.Kind.START, TaskLog.Kind.END -> ActivityKind.NOTE
            TaskLog.Kind.PROMPT -> ActivityKind.PROMPT
            TaskLog.Kind.REPLY -> ActivityKind.REPLY
            TaskLog.Kind.SAFETY -> ActivityKind.SAFETY
            TaskLog.Kind.ACTION -> ActivityKind.ACTION
            TaskLog.Kind.ERROR -> ActivityKind.ERROR
        }
    }
}
