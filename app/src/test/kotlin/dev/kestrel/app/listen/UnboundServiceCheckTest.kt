package dev.kestrel.app.listen

import dev.kestrel.app.AccessibilityServiceState
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The notice at a process's start that Android left Kestrel's accessibility service unbound after the owner's stop (spec 14.6). */
class UnboundServiceCheckTest {
    private val unbound = AccessibilityServiceState(enabled = true, running = false)
    private val stop = ExitRecord(ExitReason.OWNER_STOPPED, timestamp = BOOTED_AT + 60_000)

    @Test
    fun afterTheOwnersStopTheNoticeComesWhenTheSettingNamesTheServiceButAndroidDoesNotRunIt() = runTest {
        var notices = 0
        val check = UnboundServiceCheck(service = { unbound }, notify = { notices++ }, bootedAt = { BOOTED_AT }, main = StandardTestDispatcher(testScheduler))
        launch { check.afterStart(stop) }
        // Not before the grace period, in which Android may still bind the service.
        advanceTimeBy(UnboundServiceCheck.GRACE_MILLIS - 1)
        assertEquals(0, notices)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, notices)
    }

    @Test
    fun androids13StopNearAnUpdateGetsTheNoticeToo() = runTest {
        var notices = 0
        val check = UnboundServiceCheck(service = { unbound }, notify = { notices++ }, bootedAt = { BOOTED_AT }, main = StandardTestDispatcher(testScheduler))
        check.afterStart(ExitRecord(ExitReason.UNCLEAR_STOP, timestamp = BOOTED_AT + 60_000))
        assertEquals(1, notices)
    }

    @Test
    fun noNoticeWhenTheServiceConnectsWithinTheGracePeriod() = runTest {
        var state = unbound
        var notices = 0
        val check = UnboundServiceCheck(service = { state }, notify = { notices++ }, bootedAt = { BOOTED_AT }, main = StandardTestDispatcher(testScheduler))
        launch { check.afterStart(stop) }
        advanceTimeBy(1_000)
        state = AccessibilityServiceState(enabled = true, running = true)
        advanceTimeBy(UnboundServiceCheck.GRACE_MILLIS)
        assertEquals(0, notices)
    }

    @Test
    fun noNoticeWhenTheSettingDoesNotNameTheService() = runTest {
        var notices = 0
        val check = UnboundServiceCheck(
            service = { AccessibilityServiceState(enabled = false, running = false) },
            notify = { notices++ },
            bootedAt = { BOOTED_AT },
            main = StandardTestDispatcher(testScheduler),
        )
        check.afterStart(stop)
        assertEquals(0, notices)
    }

    @Test
    fun noNoticeAfterAnyOtherEndOfTheProcessOrAStopFromBeforeTheBoot() = runTest {
        var reads = 0
        var notices = 0
        val check = UnboundServiceCheck(
            service = { reads++; unbound },
            notify = { notices++ },
            bootedAt = { BOOTED_AT },
            main = StandardTestDispatcher(testScheduler),
        )
        // Android may be about to bind the service after a crash, a low-memory kill or a reboot, so Kestrel does not look.
        for (reason in listOf(ExitReason.CRASH, ExitReason.OTHER)) check.afterStart(ExitRecord(reason, BOOTED_AT + 60_000))
        check.afterStart(null)
        // A reboot leaves no record of its own and clears the crashed state, so the stop before it does not count.
        check.afterStart(ExitRecord(ExitReason.OWNER_STOPPED, BOOTED_AT - 1))
        assertEquals(0, reads)
        assertEquals(0, notices)
    }

    @Test
    fun theReadAndThePostRunOnTheMainThreadWhereTheConnectedServiceCancelsTheNotice() = runTest {
        val main = Executors.newSingleThreadExecutor { Thread(it, "main-test") }
        try {
            val threads = mutableListOf<String>()
            val check = UnboundServiceCheck(
                service = { threads += Thread.currentThread().name; unbound },
                notify = { threads += Thread.currentThread().name },
                bootedAt = { BOOTED_AT },
                main = main.asCoroutineDispatcher(),
            )
            check.afterStart(stop)
            assertEquals(listOf("main-test", "main-test"), threads)
        } finally {
            main.shutdown()
        }
    }

    private companion object {
        const val BOOTED_AT = 5_000_000_000L
    }
}
