package dev.kestrel.app.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kestrel's accessibility service in `dumpsys accessibility`, and the device tests' step when it does not connect (plan 7E). */
class ManagedServiceTest {
    // The sample dumps that the scripts' reader is tested with too (scripts\tests\Kestrel.Tests.ps1), with their states.
    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader?.getResource("accessibility-dumps/$name")) { "No sample dump $name" }.readText()

    @Test
    fun everySampleDumpGivesTheStateThatTheScriptsReaderGivesIt() {
        // The whole-plan review's M8: only the current user's block, the crashed set, and the component's exact name count.
        val cases = resource("cases.tsv").lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }
        assertEquals(7, cases.size)
        for ((file, expected) in cases) {
            val state = ManagedService.of(resource(file), SERVICE)
            assertEquals(file, expected, state?.toString() ?: "unreadable")
        }
    }

    @Test
    fun theServiceIsOffOnlyWhenTheManagerNeitherHasItEnabledNorBindsNorHoldsItAsCrashed() {
        assertTrue(ManagedService(enabled = false, binding = false, crashed = false).off)
        assertFalse(ManagedService(enabled = false, binding = false, crashed = true).off)
        assertFalse(ManagedService(enabled = false, binding = true, crashed = false).off)
        assertFalse(ManagedService(enabled = true, binding = false, crashed = false).off)
    }

    @Test
    fun aStuckBindOrACrashIsRetriedOnceAndEveryOtherStallFailsWithWhatTheManagerShowed() {
        val stuck = ManagedService(enabled = true, binding = true, crashed = false)
        val crashed = ManagedService(enabled = true, binding = false, crashed = true)

        val first = ServiceStall.after(stuck, seconds = 10, retried = 0)
        assertTrue(first is ServiceStall.Retry)
        assertTrue(first.message, "binding stuck" in first.message && "10 s" in first.message)
        assertTrue(ServiceStall.after(stuck, seconds = 10, retried = 1) is ServiceStall.Fail)
        // An off clears the manager's crashed set too, as Connect-AccessibilityService relies on.
        val restart = ServiceStall.after(crashed, seconds = 10, retried = 0)
        assertTrue(restart is ServiceStall.Retry)
        assertTrue(restart.message, "crashed" in restart.message)
        assertTrue(ServiceStall.after(crashed, seconds = 10, retried = 1) is ServiceStall.Fail)
        assertTrue(ServiceStall.after(ManagedService(enabled = true, binding = false, crashed = false), seconds = 10, retried = 0) is ServiceStall.Fail)
        assertTrue(ServiceStall.after(ManagedService(enabled = false, binding = false, crashed = false), seconds = 10, retried = 0) is ServiceStall.Fail)
        val unreadable = ServiceStall.after(null, seconds = 10, retried = 0)
        assertTrue(unreadable is ServiceStall.Fail)
        assertTrue(unreadable.message, "could not be read" in unreadable.message)
    }

    private companion object {
        const val SERVICE = "dev.kestrel/dev.kestrel.app.KestrelAccessibilityService"
    }
}
