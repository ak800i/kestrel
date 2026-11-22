package dev.kestrel.app.agent

import dev.kestrel.agent.port.PermissionEnd
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PermissionRequestsTest {
    @Test
    fun permissionsThatAndroidGrantedAskNothing() = runTest {
        var started = 0

        val end = PermissionRequests.ask(start = { started++; true }, granted = { PermissionEnd.GRANTED })

        assertEquals(PermissionEnd.GRANTED, end)
        assertEquals(0, started)
    }

    @Test
    fun aRequestEndsWhenItsActivityEndsAndReadsThePermissionsAgain() = runTest {
        var request = 0L
        var now = PermissionEnd.REFUSED
        val end = async { PermissionRequests.ask(start = { request = it; true }, granted = { now }) }
        runCurrent()
        assertTrue(PermissionRequests.waits(request))

        // As after Android's dialog, in which the owner allowed only the photos that he selected.
        now = PermissionEnd.PARTLY
        PermissionRequests.ended(request)

        assertEquals(PermissionEnd.PARTLY, end.await())
        assertFalse(PermissionRequests.waits(request))
    }

    @Test
    fun anActivityThatDoesNotStartEndsTheRequestAtOnce() = runTest {
        var request = 0L

        val end = PermissionRequests.ask(start = { request = it; false }, granted = { PermissionEnd.REFUSED })

        assertEquals(PermissionEnd.REFUSED, end)
        assertFalse(PermissionRequests.waits(request))
    }
}
