package dev.kestrel.app.agent

import dev.kestrel.agent.port.PermissionEnd
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLClassLoader

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

    @Test
    fun aRequestThatEndsWithoutAnAnswerFinishesItsActivity() = runTest {
        var request = 0L
        var finished = 0
        val end = async { PermissionRequests.ask(start = { request = it; true }, granted = { PermissionEnd.REFUSED }) }
        runCurrent()
        PermissionRequests.register(request) { finished++ }

        // As when the owner says "Kestrel, continue", the hand-over's 3 minutes end or he says "stop".
        end.cancel()
        end.join()

        assertEquals(1, finished)
        assertFalse(PermissionRequests.waits(request))
    }

    @Test
    fun anAnsweredRequestLeavesItsActivityToFinishItself() = runTest {
        var request = 0L
        var finished = 0
        val end = async { PermissionRequests.ask(start = { request = it; true }, granted = { PermissionEnd.GRANTED }) }
        runCurrent()
        PermissionRequests.register(request) { finished++ }

        PermissionRequests.ended(request)

        assertEquals(PermissionEnd.GRANTED, end.await())
        assertEquals(0, finished)
    }

    @Test
    fun aDestroyedActivityIsNotFinishedAgain() = runTest {
        var request = 0L
        var finished = 0
        val end = async { PermissionRequests.ask(start = { request = it; true }, granted = { PermissionEnd.REFUSED }) }
        runCurrent()
        val finish: () -> Unit = { finished++ }
        PermissionRequests.register(request, finish)

        // As in onDestroy after a configuration change, before the new instance registers its own.
        PermissionRequests.unregister(request, finish)
        end.cancel()
        end.join()

        assertEquals(0, finished)
    }

    @Test
    fun requestNumbersStartFromABaseOfTheirOwnInEachProcess() {
        // Each class loader gets its own PermissionRequests, as each of Kestrel's processes does.
        val firsts = List(3) { firstRequestInAFreshLoader() }

        assertEquals(3, firsts.toSet().size)
        firsts.forEach { assertTrue("a request number is never 0, the number of an intent without one", it > 0) }
    }

    private fun firstRequestInAFreshLoader(): Long {
        val jars = listOf(PermissionRequests::class.java, Unit::class.java, CompletableDeferred::class.java, PermissionEnd::class.java)
            .map { it.protectionDomain.codeSource.location }
            .distinct()
        return URLClassLoader(jars.toTypedArray(), null).use { loader ->
            val requests = loader.loadClass(PermissionRequests::class.java.name)
            val next = requests.getDeclaredMethod("next").apply { isAccessible = true }
            next.invoke(requests.getField("INSTANCE").get(null)) as Long
        }
    }
}
