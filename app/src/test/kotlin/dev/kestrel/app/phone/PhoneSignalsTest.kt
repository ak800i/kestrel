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
        val watch = phone.watchCalls { seen += it }
        calls.change(CallState.RINGING)
        // With the failed watch left behind, Android's listener would stay registered after this last watch stops.
        watch.stop()

        assertEquals(listOf(CallState.RINGING), seen)
        assertEquals(1, calls.stops)
    }

    @Test
    fun aScreenWatchWhoseSourceCannotStartLeavesNoWatcherBehind() {
        screens.failure = IllegalStateException("Receiver failed")
        assertThrows(IllegalStateException::class.java) { phone.watchScreen { } }

        screens.failure = null
        val seen = mutableListOf<Boolean>()
        val watch = phone.watchScreen { seen += it }
        screens.change(true)
        watch.stop()

        assertEquals(1, screens.starts)
        assertEquals(listOf(false, true), seen)
        assertEquals(1, screens.stops)
    }

    @Test
    fun aWatchFromAnotherThreadGetsItsFirstReadingOnTheSourcesThreadInOrderWithTheReports() {
        val listening = mutableListOf<Boolean>()
        val conversation = mutableListOf<Boolean>()
        val stopped = mutableListOf<Boolean>()
        screens.now = true
        phone.watchScreen { listening += it }

        screens.queued = true
        phone.watchScreen { conversation += it }
        phone.watchScreen { stopped += it }.stop()
        // The screen goes off while the source's thread has yet to come to the new watches' readings.
        screens.change(false)
        assertEquals(emptyList<Boolean>(), conversation)
        screens.runQueued()

        // The reading is the state when the source's thread reads it, so the old "on" never comes after the change.
        assertEquals(listOf(false, false), conversation)
        assertEquals(listOf(true, false), listening)
        assertEquals(emptyList<Boolean>(), stopped)
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
        var failure: Exception? = null

        // The source's one thread runs every reading and report at once, or, while queued, in order at runQueued.
        var queued = false
        private val thread = ArrayDeque<() -> Unit>()
        private var report: ((Boolean) -> Unit)? = null
        private var cameOn: (() -> Unit)? = null

        override fun start(report: (Boolean) -> Unit, cameOn: () -> Unit) {
            failure?.let { throw it }
            starts++
            this.report = report
            this.cameOn = cameOn
        }

        override fun stop() {
            stops++
            report = null
            cameOn = null
        }

        override fun read(reading: (Boolean) -> Unit) = onThread { reading(now) }

        fun change(on: Boolean) {
            now = on
            val report = checkNotNull(report) { "Nothing watches the screen" }
            onThread { report(on) }
        }

        fun comeOn() {
            val cameOn = checkNotNull(cameOn) { "Nothing watches the screen" }
            onThread { cameOn() }
        }

        fun runQueued() {
            while (thread.isNotEmpty()) thread.removeFirst().invoke()
        }

        private fun onThread(action: () -> Unit) {
            if (queued) thread.addLast(action) else action()
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
