package dev.kestrel.app.agent

import dev.kestrel.agent.port.TaskLog
import dev.kestrel.privacy.logs.ActivityEvent
import dev.kestrel.privacy.logs.ActivityKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LogTaskLogTest {
    @Test
    fun eachEntryCarriesTheTasksIdAndItsKindAndAFailureNeverEndsTheTask() = runTest {
        val written = mutableListOf<ActivityEvent>()
        val failures = mutableListOf<String>()
        val log = LogTaskLog("task-3-0a1b2c3d", { if (it.kind == ActivityKind.ERROR) error("The disk is full") else written += it }) { what, _ -> failures += what }

        log.write(TaskLog.Kind.START, "started")
        log.write(TaskLog.Kind.PROMPT, "step 1")
        log.write(TaskLog.Kind.ERROR, "failed")

        assertEquals(
            listOf(ActivityEvent(ActivityKind.NOTE, "started", "task-3-0a1b2c3d"), ActivityEvent(ActivityKind.PROMPT, "step 1", "task-3-0a1b2c3d")),
            written,
        )
        assertEquals(listOf("Writing a task's log entry"), failures)
    }
}
