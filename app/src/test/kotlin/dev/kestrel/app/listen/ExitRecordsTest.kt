package dev.kestrel.app.listen

import org.junit.Assert.assertEquals
import org.junit.Test

class ExitRecordsTest {
    @Test
    fun androidsReasonsMapToTheRestoresReasons() {
        // The values of ApplicationExitInfo's REASON_* constants, which Android keeps.
        val reasons = mapOf(
            // REASON_USER_REQUESTED: a force stop, "Stop app" or Stop in "Active apps".
            10 to ExitReason.OWNER_STOPPED,
            // REASON_USER_STOPPED: Android stopped the user profile, not the owner.
            11 to ExitReason.OTHER,
            // REASON_PACKAGE_UPDATED.
            16 to ExitReason.OTHER,
            // REASON_CRASH, REASON_CRASH_NATIVE and REASON_ANR.
            4 to ExitReason.CRASH,
            5 to ExitReason.CRASH,
            6 to ExitReason.CRASH,
        )

        assertEquals(reasons, reasons.mapValues { (reason, _) -> exitReasonOf(reason) })
    }
}
