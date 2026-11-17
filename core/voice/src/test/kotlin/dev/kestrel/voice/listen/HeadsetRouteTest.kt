package dev.kestrel.voice.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class HeadsetRouteTest {
    /** Android's communication devices: the headsets that it offers, and the requests and watchers that it got. */
    private class FakeDevices(var headsets: List<Headset> = emptyList(), var accepts: Boolean = true) : CommunicationDevices {
        val chosen = mutableListOf<Headset>()
        var cleared = 0
        val watchers = CopyOnWriteArrayList<(Int?) -> Unit>()

        /** The requests and withdrawals in their order, and anything else that a test adds. */
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())

        /** Runs at the start of each withdrawal. */
        var clearing: () -> Unit = {}
        var watchFails = false

        override fun headsets() = headsets

        override fun choose(headset: Headset): Boolean {
            chosen += headset
            events += "choose"
            return accepts
        }

        override fun clear() {
            clearing()
            cleared++
            events += "clear"
        }

        override fun watch(changed: (Int?) -> Unit): AutoCloseable {
            if (watchFails) throw RuntimeException("The watcher could not be added")
            watchers += changed
            return AutoCloseable { watchers -= changed }
        }

        /** Android reports a new communication device, or null for none. */
        fun change(id: Int?) = watchers.toList().forEach { it(id) }
    }

    private val classic = Headset(id = 7, leAudio = false)
    private val le = Headset(id = 26, leAudio = true)

    @Test
    fun withoutEarbudsThePhonesMicrophoneStays() {
        val devices = FakeDevices()
        val route = HeadsetRoute(devices)

        assertNull(route.open {})
        route.close()

        assertEquals(emptyList<Headset>(), devices.chosen)
        assertEquals(0, devices.watchers.size)
        assertEquals(0, devices.cleared)
    }

    @Test
    fun anLeAudioHeadsetComesBeforeAClassicOne() {
        val devices = FakeDevices(headsets = listOf(classic, le))

        assertSame(le, HeadsetRoute(devices).open {})
        assertEquals(listOf(le), devices.chosen)
    }

    @Test
    fun aClassicHeadsetIsAskedForWhenItIsTheOnlyOne() {
        val devices = FakeDevices(headsets = listOf(classic))

        assertSame(classic, HeadsetRoute(devices).open {})
        assertEquals(listOf(classic), devices.chosen)
        assertEquals(1, devices.watchers.size)
    }

    @Test
    fun aRefusedRequestLeavesThePhonesMicrophone() {
        val devices = FakeDevices(headsets = listOf(classic), accepts = false)
        val route = HeadsetRoute(devices)

        assertNull(route.open {})
        route.close()

        assertEquals(0, devices.watchers.size)
        assertEquals(0, devices.cleared)
    }

    @Test
    fun closeWithdrawsTheRequestOnceAndStopsWatching() {
        val devices = FakeDevices(headsets = listOf(classic))
        val route = HeadsetRoute(devices)
        route.open {}

        route.close()
        route.close()

        assertEquals(1, devices.cleared)
        assertEquals(0, devices.watchers.size)
    }

    @Test
    fun earbudsThatGoAwayGiveTheRecordingBackToThePhone() {
        val devices = FakeDevices(headsets = listOf(classic))
        val route = HeadsetRoute(devices)
        var lost = 0
        route.open { lost++ }

        // Android reports no communication device, or another one, such as the earpiece of a call.
        devices.change(null)
        devices.change(3)
        route.close()

        assertEquals(1, lost)
        assertEquals(1, devices.cleared)
        assertEquals(0, devices.watchers.size)
    }

    @Test
    fun aReportOfTheChosenHeadsetKeepsTheRoute() {
        val devices = FakeDevices(headsets = listOf(classic))
        val route = HeadsetRoute(devices)
        var lost = 0
        route.open { lost++ }

        // Android reports the headset itself once the link is up.
        devices.change(classic.id)

        assertEquals(0, lost)
        assertEquals(0, devices.cleared)
        assertEquals(1, devices.watchers.size)
    }

    @Test
    fun theRouteOpensOnceAtATimeAndAgainAfterAClose() {
        val devices = FakeDevices(headsets = listOf(classic))
        val route = HeadsetRoute(devices)
        route.open {}

        assertThrows(IllegalStateException::class.java) { route.open {} }
        route.close()

        assertSame(classic, route.open {})
        assertEquals(listOf(classic, classic), devices.chosen)
    }

    @Test
    fun aLateReportToAnEarlierOpenChangesNothing() {
        val devices = FakeDevices(headsets = listOf(classic))
        val route = HeadsetRoute(devices)
        var lost = 0
        route.open { lost++ }
        val earlier = devices.watchers.single()
        route.close()
        route.open { lost++ }

        // Android delivers a report to the first open's watcher after its close, as it may.
        earlier(3)

        assertEquals(0, lost)
        assertEquals(1, devices.cleared)
        assertEquals(1, devices.watchers.size)
    }

    @Test
    fun aLossThatHasBegunEndsBeforeTheCloseReturnsSoTheNextRequestStays() {
        val devices = FakeDevices(headsets = listOf(classic))
        val clearing = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        devices.clearing = {
            if (clearing.count > 0) {
                clearing.countDown()
                proceed.await(5, TimeUnit.SECONDS)
            }
        }
        val first = HeadsetRoute(devices)
        first.open { devices.events += "lost" }

        // Android's report of another device comes on the main thread and begins the loss...
        val main = thread { devices.change(3) }
        assertTrue(clearing.await(5, TimeUnit.SECONDS))
        // ...while the controller's thread stops the recording and starts the next one, which has a route of its own.
        val controller = thread {
            first.close()
            HeadsetRoute(devices).open {}
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (controller.isAlive && controller.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(1)
        proceed.countDown()
        main.join(5_000)
        controller.join(5_000)

        // The earlier route's withdrawal and onLost come before the next request, so neither reaches the next
        // recording.
        assertEquals(listOf("choose", "clear", "lost", "choose"), devices.events)
    }

    @Test
    fun aWatcherThatAndroidRefusesGivesTheRequestBack() {
        val devices = FakeDevices(headsets = listOf(classic))
        devices.watchFails = true
        val route = HeadsetRoute(devices)

        assertThrows(RuntimeException::class.java) { route.open {} }

        assertEquals(1, devices.cleared)
        // The route is closed, so it opens again.
        devices.watchFails = false
        assertSame(classic, route.open {})
    }
}