package dev.kestrel.app.conversation

import dev.kestrel.app.agent.CallBackstop
import dev.kestrel.app.listen.CallApi
import dev.kestrel.app.listen.CallMonitor
import dev.kestrel.app.orchestrator.CallState
import dev.kestrel.app.phone.PhoneSignals
import dev.kestrel.app.phone.ScreenReading
import dev.kestrel.app.phone.ScreenReadings
import dev.kestrel.app.phone.ScreenStatus
import dev.kestrel.app.phone.ScreenSource
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.GoalPhase
import dev.kestrel.conversation.model.PhoneSignal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The phone's lock and calls as the conversation's signals, with the call backstop (design 6.5 of sub-project 7). */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationSignalsTest {
    private val screens = FakeScreens()
    private val calls = FakeCalls()
    private val callApi = FakeCallApi()
    private var now = 0L
    private val backstop = CallBackstop(callApi) { now }
    private val signals = mutableListOf<PhoneSignal>()
    private val reports = mutableListOf<String>()
    private val views = MutableStateFlow(ConversationView())
    private val open = ConversationView(id = ConversationId.random(), command = 1)

    @Test
    fun theLockAndTheUnlockReachTheConversationOnlyWhileItIsOpen() = runTest {
        follow()
        screens.now = true
        screens.change(false)
        assertEquals(0, screens.starts)

        views.value = open
        runCurrent()
        assertEquals(1, screens.starts)
        screens.change(false)
        screens.change(false)
        screens.change(true)
        assertEquals(listOf(PhoneSignal.LOCKED, PhoneSignal.UNLOCKED), signals)

        // A closed conversation watches nothing; a lock meanwhile counts at the next watch.
        views.value = ConversationView()
        runCurrent()
        assertEquals(1, screens.stops)
        screens.now = false
        views.value = open
        runCurrent()
        assertEquals(listOf(PhoneSignal.LOCKED, PhoneSignal.UNLOCKED, PhoneSignal.LOCKED), signals)
    }

    // The seventh re-check: a keyguard that reports the unlock 1.5 s after USER_PRESENT, while the screen source looks
    // every 250 ms. The screen was read as locked, so the conversation hears one change, the unlock when the keyguard
    // reports it, and no lock after it: its goal is never paused by the keyguard's lag.
    @Test
    fun aKeyguardThatIsLateAfterTheUnlockSendsOneUnlockAndNoLock() = runTest {
        val screen = ReadingScreens()
        follow(screen)
        views.value = open
        runCurrent()
        assertEquals(listOf(PhoneSignal.LOCKED), signals)

        screen.heard(ScreenReadings.Broadcast.USER_PRESENT)
        runCurrent()
        while (screen.readings.settling()) {
            screen.clock += ScreenReadings.KEYGUARD_POLL_MILLIS
            if (screen.clock >= 1_500) screen.keyguardLocked = false
            screen.look()
            runCurrent()
        }

        assertEquals(1_500L, screen.clock)
        assertEquals(listOf(PhoneSignal.LOCKED, PhoneSignal.UNLOCKED), signals)
    }

    @Test
    fun theFirstWatchSendsTheLockStateAlsoOnAPhoneWithoutALockScreen() = runTest {
        // A phone without a lock screen, as kestrel-api37, reads as unlocked from the start; the conversation still learns it
        // (plan 7B: until a lock signal comes, the owner is not known to be there).
        follow()
        views.value = open
        runCurrent()
        assertEquals(listOf(PhoneSignal.UNLOCKED), signals)

        // A later watch sends only a change since the conversation's last reading.
        views.value = ConversationView()
        runCurrent()
        views.value = open
        runCurrent()
        assertEquals(listOf(PhoneSignal.UNLOCKED), signals)
    }

    @Test
    fun wordsKeptForTheOwnerKeepTheWatchAfterTheConversationClosed() = runTest {
        follow()
        screens.now = false
        views.value = open
        runCurrent()

        // A change of test mode closed the conversation while the phone was locked; its goal's final reply waits.
        views.value = ConversationView(keptForOwner = true)
        runCurrent()
        assertEquals(0, screens.stops)
        screens.change(true)
        assertEquals(listOf(PhoneSignal.LOCKED, PhoneSignal.UNLOCKED), signals)

        views.value = ConversationView()
        runCurrent()
        assertEquals(1, screens.stops)
    }

    @Test
    fun theScreenComingOnReachesTheConversationWhileItWatches() = runTest {
        follow()
        screens.now = false
        screens.comeOn()
        views.value = open
        runCurrent()

        // Locked or not, the engine then ends the timers whose deadlines passed while the phone slept (plan 7B).
        screens.comeOn()
        assertEquals(listOf(PhoneSignal.LOCKED, PhoneSignal.SCREEN_ON), signals)
    }

    @Test
    fun callsReachTheConversationOnceEachAndAGoalKeepsTheWatch() = runTest {
        follow()
        views.value = ConversationView(goal = GoalPhase.HANDED_OVER)
        runCurrent()

        calls.change(CallState.IDLE)
        calls.change(CallState.RINGING)
        calls.change(CallState.ACTIVE)
        calls.change(CallState.ACTIVE)
        calls.change(CallState.IDLE)

        assertEquals(listOf(PhoneSignal.UNLOCKED, PhoneSignal.RINGING, PhoneSignal.CALL_ACTIVE, PhoneSignal.CALL_IDLE), signals)
    }

    @Test
    fun aCallThatBecomesActiveRightAfterTheAgentsActionEndsTheGoalAndKestrelEndsIt() = runTest {
        follow()
        views.value = open.copy(goal = GoalPhase.ACTING)
        runCurrent()
        backstop.acted()
        now = 1_000

        calls.change(CallState.ACTIVE)
        runCurrent()

        assertEquals(listOf(PhoneSignal.UNLOCKED, PhoneSignal.AGENT_CALL), signals)
        assertEquals(1, callApi.ended)
    }

    @Test
    fun aCallThatRangALateOneOrOneDuringAHandOverIsTheOwners() = runTest {
        follow()
        views.value = open.copy(goal = GoalPhase.ACTING)
        runCurrent()
        backstop.acted()
        now = 1_000
        calls.change(CallState.RINGING)
        calls.change(CallState.ACTIVE)
        calls.change(CallState.IDLE)

        now = 7_000
        calls.change(CallState.ACTIVE)
        calls.change(CallState.IDLE)

        views.value = open.copy(goal = GoalPhase.HANDED_OVER)
        runCurrent()
        backstop.acted()
        calls.change(CallState.ACTIVE)
        runCurrent()

        assertEquals(
            listOf(
                PhoneSignal.UNLOCKED, PhoneSignal.RINGING, PhoneSignal.CALL_ACTIVE, PhoneSignal.CALL_IDLE,
                PhoneSignal.CALL_ACTIVE, PhoneSignal.CALL_IDLE, PhoneSignal.CALL_ACTIVE,
            ),
            signals,
        )
        assertEquals(0, callApi.ended)
    }

    private fun kotlinx.coroutines.test.TestScope.follow(screens: ScreenSource = this@ConversationSignalsTest.screens) {
        val conversationSignals = ConversationSignals(PhoneSignals(screens, calls), backstop, backgroundScope, { signals += it }) { what, _ -> reports += what }
        backgroundScope.launch { conversationSignals.follow(views) }
        runCurrent()
    }

    // The screen source's own rule (ScreenReadings) behind a source whose broadcasts and looks the test gives, on the test's
    // one thread, as AndroidScreenSource gives them on the main thread.
    private class ReadingScreens : ScreenSource {
        var clock = 0L
        var keyguardLocked = true
        val readings = ScreenReadings({ true }, { keyguardLocked }, { clock }, { true })
        private var report: ((ScreenStatus) -> Unit)? = null

        override fun start(report: (ScreenStatus) -> Unit, cameOn: () -> Unit) {
            this.report = report
        }

        override fun stop() {
            report = null
        }

        override fun status(): ScreenStatus = readings.status()

        override fun read(reading: (ScreenStatus) -> Unit) = reading(readings.next())

        fun heard(broadcast: ScreenReadings.Broadcast) {
            checkNotNull(report)(readings.heard(broadcast))
        }

        // One of the source's looks while the keyguard may still change: a changed reading is reported with a new number.
        fun look() {
            if (readings.stale()) checkNotNull(report)(readings.next())
        }
    }

    private class FakeScreens : ScreenSource {
        var now = true
        private var seq = 0L

        override fun status(): ScreenStatus = ScreenStatus(readingOf(now), offs = 0, seq = seq)
        var starts = 0
        var stops = 0
        private var report: ((ScreenStatus) -> Unit)? = null
        private var cameOn: (() -> Unit)? = null

        override fun start(report: (ScreenStatus) -> Unit, cameOn: () -> Unit) {
            starts++
            this.report = report
            this.cameOn = cameOn
        }

        override fun stop() {
            stops++
            report = null
            cameOn = null
        }

        override fun read(reading: (ScreenStatus) -> Unit) = reading(next())

        fun change(on: Boolean) {
            now = on
            report?.invoke(next())
        }

        private fun next(): ScreenStatus {
            seq++
            return status()
        }

        fun comeOn() {
            cameOn?.invoke()
        }

        // The conversation tells only unlocked from not: locked stands for both.
        private fun readingOf(unlocked: Boolean) = if (unlocked) ScreenReading.UNLOCKED else ScreenReading.LOCKED
    }

    private class FakeCalls : CallMonitor {
        private var report: ((CallState) -> Unit)? = null

        override fun start(report: (CallState) -> Unit) {
            this.report = report
        }

        override fun stop() {
            report = null
        }

        fun change(state: CallState) {
            report?.invoke(state)
        }
    }

    private class FakeCallApi : CallApi {
        var ended = 0

        override fun allowed(): Boolean = true

        override fun answer() = Unit

        override fun end(): Boolean {
            ended++
            return true
        }

        override fun active(): Boolean = false

        override fun showInCallScreen() = Unit
    }
}
