package dev.kestrel.screen.act

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

class SystemControlsTest {
    @Test
    fun theOverviewOfTheRecentsComponentIsNoSwitchWhateverTheHomeAppIs() {
        // Another launcher is the default home app; the system launcher still shows the overview.
        val overview = setOf(recentsPackage("com.google.android.apps.nexuslauncher/com.android.quickstep.RecentsActivity")!!, "org.other.launcher")

        assertFalse(switchedTo("com.google.android.apps.nexuslauncher", "dev.kestrel.testapp", overview))
        assertFalse(switchedTo("org.other.launcher", "dev.kestrel.testapp", overview))
        assertFalse(switchedTo("dev.kestrel.testapp", "dev.kestrel.testapp", overview))
        assertFalse(switchedTo(null, "dev.kestrel.testapp", overview))
        assertTrue(switchedTo("com.android.settings", "dev.kestrel.testapp", overview))
        assertEquals("com.android.launcher3", recentsPackage("com.android.launcher3/.RecentsActivity"))
        assertNull(recentsPackage(""))
        assertNull(recentsPackage(null))
    }

    @Test
    fun theCacheIsClearedAlsoWhenTheCommandIsCancelledDuringTheWait() = runTest {
        var reported = false
        var cleaned = false
        val pan = launch(start = CoroutineStart.UNDISPATCHED) {
            changeMagnification({ true }, REFUSED, { reported = true }, { awaitCancellation() }, EmptyCoroutineContext) { cleaned = true }
        }

        pan.cancelAndJoin()

        assertTrue(reported)
        assertTrue(cleaned)
    }

    @Test
    fun theCacheIsClearedAlsoWhenTheCommandIsCancelledWhileTheChangeRunsOnTheMainThread() = runTest {
        var cleaned = false
        lateinit var zoom: Job
        // The cancel arrives while Android changes the magnification on the main thread, which then returns the change.
        zoom = launch {
            changeMagnification({ zoom.cancel(); true }, REFUSED, {}, { DONE }, StandardTestDispatcher(testScheduler)) { cleaned = true }
        }

        zoom.join()

        assertTrue(zoom.isCancelled)
        assertTrue(cleaned)
    }

    @Test
    fun aRefusedChangeNeedsNoCleanup() = runTest {
        var cleaned = false

        val result = changeMagnification({ false }, REFUSED, {}, { DONE }, StandardTestDispatcher(testScheduler)) { cleaned = true }

        assertEquals(REFUSED, result)
        assertFalse(cleaned)
    }

    @Test
    fun aRefusedGlobalActionIsNamedInPlainWords() {
        assertEquals("Android refused to open the power menu.", GlobalAction.POWER_MENU.refused.reason)
        assertEquals("Android refused to show recent apps.", GlobalAction.RECENT_APPS.refused.reason)
        for (action in GlobalAction.entries) {
            val reason = action.refused.reason
            assertFalse(reason, action.name in reason || '_' in reason)
        }
    }

    private companion object {
        val REFUSED = ActionResult.Failed("Android refused")
        val DONE = ActionResult.Done(ActionMethod.MAGNIFICATION)
    }
}
