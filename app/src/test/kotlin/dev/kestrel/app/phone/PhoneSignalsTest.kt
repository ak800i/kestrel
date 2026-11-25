package dev.kestrel.app.phone

import dev.kestrel.app.listen.CallMonitor
import dev.kestrel.app.orchestrator.CallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** One screen receiver and one call listener for every watcher (design 5.5 and 6.5 of sub-project 7; plan 7D). */
class PhoneSignalsTest {
    private val screens = FakeScreens()
    private val calls = FakeCalls()
    private val phone = PhoneSignals(screens, calls)

    @Test
    fun oneReceiverServesEveryScreenWatchAndANewWatchGetsTheReadingAtOnce() {
        val listening = mutableListOf<Boolean>()
        val conversation = mutableListOf<Boolean>()
        screens.now = true

        val first = phone.watchScreen { listening += it }
        val second = phone.watchScreen { conversation += it }
        assertEquals(1, screens.starts)
        assertEquals(listOf(true), listening)
        assertEquals(listOf(true), conversation)

        screens.change(false)
        first.stop()
        first.stop()
        screens.change(true)

        assertEquals(listOf(true, false), listening)
        assertEquals(listOf(true, false, true), conversation)
        assertEquals(0, screens.stops)
        second.stop()
        assertEquals(1, screens.stops)
    }

    @Test
    fun everyCallWatchRegistersAndroidsListenerAgainAndTheLastStopEndsIt() {
        val first = mutableListOf<CallState>()
        val second = mutableListOf<CallState>()

        val one = phone.watchCalls { first += it }
        calls.change(CallState.IDLE)
        val two = phone.watchCalls { second += it }
        // Android reports the state at once to a listener that registers, so the new watch's first report is Android's own.
        assertEquals(2, calls.starts)
        calls.change(CallState.RINGING)

        assertEquals(listOf(CallState.IDLE, CallState.RINGING), first)
        assertEquals(listOf(CallState.RINGING), second)
        one.stop()
        assertEquals(0, calls.stops)
        two.stop()
        assertEquals(1, calls.stops)
    }

    @Test
    fun listeningsCallMonitorIsOneWatchThatANewStartReplaces() {
        val monitor = phone.callMonitor()
        val seen = mutableListOf<CallState>()

        monitor.start { seen += it }
        // As Listening.watchCalls after the owner allowed the phone state: Android's listener registers again.
        monitor.start { seen += it }
        assertEquals(2, calls.starts)
        assertEquals(0, calls.stops)
        calls.change(CallState.ACTIVE)

        assertEquals(listOf(CallState.ACTIVE), seen)
        monitor.stop()
        assertEquals(1, calls.stops)
    }

    @Test
    fun aCallWatchWhoseListenerCannotStartLeavesNoWatcherBehind() {
        calls.failure = IllegalStateException("Telephony failed")
        assertThrows(IllegalStateException::class.java) { phone.watchCalls { } }

        calls.failure = null
        val seen = mutableListOf<CallState>()
        phone.watchCalls { seen += it }
        calls.change(CallState.RINGING)

        assertEquals(listOf(CallState.RINGING), seen)
    }

    @Test
    fun theScreenComingOnReachesEveryScreenWatchThatHearsIt() {
        var woken = 0
        val conversation = phone.watchScreen({ woken++ }) { }
        phone.watchScreen { }

        screens.comeOn()
        conversation.stop()
        screens.comeOn()

        assertEquals(1, woken)
    }

    private class FakeScreens : ScreenSource {
        var now = false
        var starts = 0
        var stops = 0
        private var report: ((Boolean) -> Unit)? = null
        private var cameOn: (() -> Unit)? = null

        override fun start(report: (Boolean) -> Unit, cameOn: () -> Unit) {
            starts++
            this.report = report
            this.cameOn = cameOn
        }

        override fun stop() {
            stops++
            report = null
            cameOn = null
        }

        override fun current(): Boolean = now

        fun change(on: Boolean) {
            now = on
            checkNotNull(report) { "Nothing watches the screen" }.invoke(on)
        }

        fun comeOn() {
            checkNotNull(cameOn) { "Nothing watches the screen" }.invoke()
        }
    }

    private class FakeCalls : CallMonitor {
        var starts = 0
        var stops = 0
        var failure: Exception? = null
        private var report: ((CallState) -> Unit)? = null

        override fun start(report: (CallState) -> Unit) {
            failure?.let { throw it }
            starts++
            this.report = report
        }

        override fun stop() {
            stops++
            report = null
        }

        fun change(state: CallState) {
            checkNotNull(report) { "Nothing watches the calls" }.invoke(state)
        }
    }
}
