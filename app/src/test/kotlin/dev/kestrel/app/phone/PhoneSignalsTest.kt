package dev.kestrel.app.phone

import dev.kestrel.app.listen.CallMonitor
import dev.kestrel.app.orchestrator.CallState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** One screen receiver and one call listener for every watcher (design 5.5 and 6.5 of sub-project 7; plan 7D). */
@OptIn(ExperimentalCoroutinesApi::class)
class PhoneSignalsTest {
    private val screens = FakeScreens()
    private val calls = FakeCalls()
    private val phone = PhoneSignals(screens, calls)

    @Test
    fun oneReceiverServesEveryScreenWatchAndANewWatchGetsTheReadingAtOnce() {
        val listening = mutableListOf<ScreenReading>()
        val conversation = mutableListOf<ScreenReading>()
        screens.now = ScreenReading.UNLOCKED

        val first = phone.watchScreen { listening += it }
        val second = phone.watchScreen { conversation += it }
        assertEquals(1, screens.starts)
        assertEquals(listOf(ScreenReading.UNLOCKED), listening)
        assertEquals(listOf(ScreenReading.UNLOCKED), conversation)

        screens.change(ScreenReading.OFF)
        first.stop()
        first.stop()
        screens.change(ScreenReading.UNLOCKED)

        assertEquals(listOf(ScreenReading.UNLOCKED, ScreenReading.OFF), listening)
        assertEquals(listOf(ScreenReading.UNLOCKED, ScreenReading.OFF, ScreenReading.UNLOCKED), conversation)
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
        val seen = mutableListOf<ScreenReading>()
        val watch = phone.watchScreen { seen += it }
        screens.change(ScreenReading.UNLOCKED)
        watch.stop()

        assertEquals(1, screens.starts)
        assertEquals(listOf(ScreenReading.OFF, ScreenReading.UNLOCKED), seen)
        assertEquals(1, screens.stops)
    }

    @Test
    fun aWatchFromAnotherThreadGetsItsFirstReadingOnTheSourcesThreadInOrderWithTheReports() {
        val listening = mutableListOf<ScreenReading>()
        val conversation = mutableListOf<ScreenReading>()
        val stopped = mutableListOf<ScreenReading>()
        screens.now = ScreenReading.UNLOCKED
        phone.watchScreen { listening += it }

        screens.queued = true
        phone.watchScreen { conversation += it }
        phone.watchScreen { stopped += it }.stop()
        // The screen goes off while the source's thread has yet to come to the new watches' readings.
        screens.change(ScreenReading.OFF)
        assertEquals(emptyList<ScreenReading>(), conversation)
        screens.runQueued()

        // The reading is the state when the source's thread reads it, so the old "on" never comes after the change.
        assertEquals(listOf(ScreenReading.OFF, ScreenReading.OFF), conversation)
        assertEquals(listOf(ScreenReading.UNLOCKED, ScreenReading.OFF), listening)
        assertEquals(emptyList<ScreenReading>(), stopped)
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

    @Test
    fun theCallStatesFlowWatchesTheCallsWhileItIsCollected() = runTest {
        val seen = mutableListOf<CallState>()
        val collecting = backgroundScope.launch { phone.callStates().collect { seen += it } }
        runCurrent()
        assertEquals(1, calls.starts)

        calls.change(CallState.RINGING)
        runCurrent()
        assertEquals(listOf(CallState.RINGING), seen)

        collecting.cancel()
        runCurrent()
        assertEquals(1, calls.stops)
    }

    // The question card's reading of the lock (plan 7F's whole-plan re-check): the screen source's reading first, then
    // every change, and the watch ends with the collection.
    @Test
    fun theScreenStatesFlowGivesTheReadingFirstThenEveryChangeAndStopsWithItsCollection() = runTest {
        val seen = mutableListOf<ScreenStatus>()
        screens.now = ScreenReading.LOCKED
        val collecting = backgroundScope.launch { phone.screenStates().collect { seen += it } }
        runCurrent()
        assertEquals(1, screens.starts)

        screens.change(ScreenReading.UNLOCKED)
        screens.change(ScreenReading.OFF)
        runCurrent()
        // Each reading carries the count of screen-offs of its moment.
        val expected = listOf(
            ScreenStatus(ScreenReading.LOCKED, 0),
            ScreenStatus(ScreenReading.UNLOCKED, 0),
            ScreenStatus(ScreenReading.OFF, 1),
        )
        assertEquals(expected, seen)
        assertEquals(ScreenStatus(ScreenReading.OFF, 1), phone.screenStatus())

        collecting.cancel()
        runCurrent()
        assertEquals(1, screens.stops)
    }

    private class FakeScreens : ScreenSource {
        var now = ScreenReading.OFF
        override var offs = 0L

        override fun status(): ScreenStatus = ScreenStatus(now, offs)
        var starts = 0
        var stops = 0
        var failure: Exception? = null

        // The source's one thread runs every reading and report at once, or, while queued, in order at runQueued.
        var queued = false
        private val thread = ArrayDeque<() -> Unit>()
        private var report: ((ScreenReading) -> Unit)? = null
        private var cameOn: (() -> Unit)? = null

        override fun start(report: (ScreenReading) -> Unit, cameOn: () -> Unit) {
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

        override fun read(reading: (ScreenReading) -> Unit) = onThread { reading(now) }

        fun change(reading: ScreenReading) {
            now = reading
            if (reading == ScreenReading.OFF) offs++
            val report = checkNotNull(report) { "Nothing watches the screen" }
            onThread { report(reading) }
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
