package dev.kestrel.app.conversation

import dev.kestrel.app.listen.ExitReason
import dev.kestrel.app.listen.ExitRecord
import dev.kestrel.app.listen.ExitRecords
import dev.kestrel.conversation.model.ProcessEnd
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** How Kestrel's last process ended, for the conversation's restore (plan 7E; plan 7B's `ProcessEnd`). */
class ProcessEndsTest {
    @Test
    fun onlyTheOwnersSureStopEndsTheRestoredGoal() = runTest {
        val at = 5_000_000_000L
        assertEquals(ProcessEnd.OwnerStopped(at), processEndOf(ExitRecord(ExitReason.OWNER_STOPPED, at)))
        // A stop that cannot be told from an update on Android 13, any other end, and no record hold the goal and ask.
        for (reason in listOf(ExitReason.UNCLEAR_STOP, ExitReason.CRASH, ExitReason.OTHER)) {
            assertEquals(ProcessEnd.Other, processEndOf(ExitRecord(reason, at)))
        }
        assertEquals(ProcessEnd.Other, processEndOf(null))

        // When Android cannot give its record, the failure is reported and the goal is held.
        val failures = mutableListOf<String>()
        val failing = object : ExitRecords {
            override suspend fun newest(): ExitRecord? = throw IllegalStateException("no records")
        }
        assertEquals(ProcessEnd.Other, readProcessEnd(failing) { what, _ -> failures += what })
        assertEquals(listOf("Reading how Kestrel's last process ended"), failures)
    }
}
