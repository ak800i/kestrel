package dev.kestrel.voice.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class HeadsetRouteTest {
    /** Android's communication devices: the headsets that it offers, and the requests and watchers that it got. */
    private class FakeDevices(var headsets: List<Headset> = emptyList(), var accepts: Boolean = true) : CommunicationDevices {
        val chosen = mutableListOf<Headset>()
        var cleared = 0
        val watchers = mutableListOf<(Int?) -> Unit>()

        override fun headsets() = headsets

        override fun choose(headset: Headset): Boolean {
            chosen += headset
            return accepts
        }

        override fun clear() {
            cleared++
        }

        override fun watch(changed: (Int?) -> Unit): AutoCloseable {
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
}
