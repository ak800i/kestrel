package dev.kestrel.app.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kestrel's accessibility service in `dumpsys accessibility`, and the device tests' step when it does not connect (plan 7E). */
class ManagedServiceTest {
    private fun dump(enabled: String, binding: String) = """
        User state[
             attributes:{id=0, touchExplorationEnabled=false}
             Bound services:{}
             Enabled services:{$enabled}
             Binding services:{$binding}
             Crashed services:{}
        User state[
             Bound services:{}
             Enabled services:{}
             Binding services:{}
    """.trimIndent()

    @Test
    fun theStateIsReadFromTheEnabledAndBindingLinesOnly() {
        assertEquals(ManagedService(enabled = true, binding = true), ManagedService.of(dump("{$SERVICE}", "{$SERVICE}"), SERVICE))
        assertEquals(ManagedService(enabled = true, binding = false), ManagedService.of(dump("{$SERVICE}", ""), SERVICE))
        val off = ManagedService.of(dump("{com.example/com.example.Reader}", ""), SERVICE)
        assertTrue(off.off)
        // Another line that names the service, such as the shortcut's targets, does not count.
        assertFalse(ManagedService.of(dump("", "") + "\n     Button targets:{$SERVICE}", SERVICE).enabled)
    }

    @Test
    fun aStuckBindIsRetriedOnceAndEveryOtherStallFailsWithWhatTheManagerShowed() {
        val stuck = ManagedService(enabled = true, binding = true)

        val first = ServiceStall.after(stuck, seconds = 10, retried = 0)
        assertTrue(first is ServiceStall.Retry)
        assertTrue(first.message, "binding stuck" in first.message && "10 s" in first.message)
        assertTrue(ServiceStall.after(stuck, seconds = 10, retried = 1) is ServiceStall.Fail)
        assertTrue(ServiceStall.after(ManagedService(enabled = true, binding = false), seconds = 10, retried = 0) is ServiceStall.Fail)
        assertTrue(ServiceStall.after(ManagedService(enabled = false, binding = false), seconds = 10, retried = 0) is ServiceStall.Fail)
    }

    private companion object {
        const val SERVICE = "dev.kestrel/dev.kestrel.app.KestrelAccessibilityService"
    }
}
