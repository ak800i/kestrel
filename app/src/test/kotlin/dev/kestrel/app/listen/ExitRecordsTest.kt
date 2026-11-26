package dev.kestrel.app.listen

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun everyReaderInAProcessGetsTheOneReadingOfTheRecord() = runTest {
        // Listening's restore, the conversation's restore and the accessibility check read the record at different
        // moments; one binder call gives all of them the same record (plan 7E).
        var reads = 0
        val answer = CompletableDeferred<Unit>()
        val record = ExitRecord(ExitReason.OWNER_STOPPED, AT)
        val previous = PreviousExit({ reads++; answer.await(); record }) { _, _ -> }

        val first = async { previous.get() }
        val second = async { previous.get() }
        runCurrent()
        answer.complete(Unit)

        assertEquals(record, first.await())
        assertEquals(record, second.await())
        assertEquals(record, previous.get())
        assertEquals(1, reads)
    }

    @Test
    fun aRecordThatAndroidCannotGiveIsReportedOnceAndCountsAsNoneForEveryReader() = runTest {
        val failures = mutableListOf<String>()
        var reads = 0
        val previous = PreviousExit({ reads++; throw IllegalStateException("no records") }) { what, _ -> failures += what }

        assertNull(previous.get())
        assertNull(previous.get())
        assertEquals(listOf("Reading how Kestrel's last process ended"), failures)
        assertEquals(1, reads)
    }

    @Test
    fun aFailureOfOneUseOfTheReadingNeverCancelsAnother() = runTest {
        // The conversation's restore failing must not take the accessibility notice with it (design 10.6 of sub-project 7).
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, _ -> })
        val record = ExitRecord(ExitReason.OWNER_STOPPED, AT)
        var checked: ExitRecord? = null
        val previous = PreviousExit({ record }) { _, _ -> }

        previous.launchEach(scope, { delay(10_000); checked = it }, { throw IllegalStateException("the restore failed") })
        advanceUntilIdle()

        assertEquals(record, checked)
    }

    private companion object {
        const val AT = 5_000_000_000L
    }
}
