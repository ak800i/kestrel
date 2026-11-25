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

        assertEquals(reasons, reasons.mapValues { (reason, _) -> exitReasonOf(reason, AT, 37, updatedAt = AT) })
    }

    @Test
    fun onAndroid13AStopWithinAMinuteOfAnUpdateCannotBeToldFromItAndAnyOtherStopIsTheOwners() {
        // Android 13 gives an update REASON_USER_REQUESTED too, and takes the update's time a few seconds before or after
        // its kill, so the minute counts on both sides (plan 7E).
        assertEquals(ExitReason.UNCLEAR_STOP, exitReasonOf(10, AT, 33, updatedAt = AT - 30_000))
        assertEquals(ExitReason.UNCLEAR_STOP, exitReasonOf(10, AT, 33, updatedAt = AT + 2_000))
        assertEquals(ExitReason.UNCLEAR_STOP, exitReasonOf(10, AT, 33, updatedAt = AT + 60_000))
        // A stop an hour after the last update, or an hour before an update that came while no process of Kestrel's ran.
        assertEquals(ExitReason.OWNER_STOPPED, exitReasonOf(10, AT, 33, updatedAt = AT - 3_600_000))
        assertEquals(ExitReason.OWNER_STOPPED, exitReasonOf(10, AT, 33, updatedAt = AT + 3_600_000))
        // From Android 14 an update has a reason of its own.
        assertEquals(ExitReason.OWNER_STOPPED, exitReasonOf(10, AT, 34, updatedAt = AT))
        assertEquals(ExitReason.OTHER, exitReasonOf(16, AT, 34, updatedAt = AT))
    }

    private companion object {
        const val AT = 5_000_000_000L
    }
}
