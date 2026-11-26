package dev.kestrel.app.listen

import dev.kestrel.conversation.model.ProcessEnd
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The notice at a process's start that Android left Kestrel's accessibility service unbound after the owner's stop (spec 14.6). */
class UnboundServiceCheckTest {
    private val stopped = ProcessEnd.OwnerStopped(5_000_000_000L)
    private val unbound = AccessibilityServiceState(enabled = true, running = false)

    @Test
    fun afterTheOwnersStopTheNoticeComesWhenTheSettingNamesTheServiceButAndroidDoesNotRunIt() = runTest {
        var notices = 0
        val check = UnboundServiceCheck(service = { unbound }, notify = { notices++ })
        launch { check.afterStart(stopped) }
        // Not before the grace period, in which Android may still bind the service.
        advanceTimeBy(UnboundServiceCheck.GRACE_MILLIS - 1)
        assertEquals(0, notices)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, notices)
    }

    @Test
    fun noNoticeWhenTheServiceConnectsWithinTheGracePeriod() = runTest {
        var state = unbound
        var notices = 0
        val check = UnboundServiceCheck(service = { state }, notify = { notices++ })
        launch { check.afterStart(stopped) }
        advanceTimeBy(1_000)
        state = AccessibilityServiceState(enabled = true, running = true)
        advanceTimeBy(UnboundServiceCheck.GRACE_MILLIS)
        assertEquals(0, notices)
    }

    @Test
    fun noNoticeWhenTheSettingDoesNotNameTheService() = runTest {
        var notices = 0
        val check = UnboundServiceCheck(service = { AccessibilityServiceState(enabled = false, running = false) }, notify = { notices++ })
        check.afterStart(stopped)
        assertEquals(0, notices)
    }

    @Test
    fun noNoticeAfterAnyOtherEndOfTheProcess() = runTest {
        var reads = 0
        var notices = 0
        val check = UnboundServiceCheck(service = { reads++; unbound }, notify = { notices++ })
        check.afterStart(ProcessEnd.Other)
        // Android may be about to bind the service after a crash, a low-memory kill or a reboot, so Kestrel does not look.
        assertEquals(0, reads)
        assertEquals(0, notices)
    }
}
