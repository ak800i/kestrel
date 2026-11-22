package dev.kestrel.app.agent

import dev.kestrel.app.listen.CallApi
import dev.kestrel.app.orchestrator.CallState
import dev.kestrel.app.orchestrator.Effect
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.app.orchestrator.PauseCause
import dev.kestrel.app.orchestrator.Rules
import dev.kestrel.app.orchestrator.State
import dev.kestrel.app.orchestrator.TaskPhase
import dev.kestrel.app.orchestrator.Work
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallBackstopTest {
    private var now = 0L
    private val calls = FakeCalls()
    private val backstop = CallBackstop(calls) { now }
    private val running = State(mode = Mode.LISTENING, work = Work.RUNNING_TASK, task = 1, taskPhase = TaskPhase.RUNNING)

    private fun pauseOf(state: State, call: CallState): PauseCause? =
        Rules.next(state, Event.CallState(call)).effects.filterIsInstance<Effect.PauseTask>().singleOrNull()?.cause

    @Test
    fun anIncomingCallOneSecondAfterAnActionOnlyPausesTheTask() {
        backstop.acted()
        now = 1_000

        val cause = pauseOf(running, CallState.RINGING)

        assertEquals(PauseCause.INCOMING_CALL, cause)
        assertEquals(CallBackstop.Verdict.PAUSE, backstop.onPause(cause!!))
        assertEquals(0, calls.ended)
    }

    @Test
    fun anOutgoingCallOneSecondAfterAnActionStopsTheTaskAndEndsTheCall() {
        backstop.acted()
        now = 1_000

        val cause = pauseOf(running, CallState.ACTIVE)

        assertEquals(PauseCause.OUTGOING_CALL, cause)
        assertEquals(CallBackstop.Verdict.STOP, backstop.onPause(cause!!))
        assertEquals(0, calls.ended)
        backstop.endCall()
        assertEquals(1, calls.ended)
    }

    @Test
    fun anOutgoingCallLongAfterTheLastActionOrWithoutThePermissionDoesNotEndTheCall() {
        backstop.acted()
        now = 6_000
        assertEquals(CallBackstop.Verdict.PAUSE, backstop.onPause(PauseCause.OUTGOING_CALL))

        calls.allowed = false
        now = 7_000
        backstop.acted()
        assertEquals(CallBackstop.Verdict.STOP, backstop.onPause(PauseCause.OUTGOING_CALL))
        backstop.endCall()
        assertEquals(0, calls.ended)
    }

    @Test
    fun aCallThatTheOwnerPlacesDuringAHandOverIsLeftAlone() {
        backstop.acted()
        val handedOver = Rules.next(running, Event.TaskHandsOver(1, "Tap Call yourself if you want this call.")).state
        now = 1_000

        val step = Rules.next(handedOver, Event.CallState(CallState.ACTIVE))

        assertFalse(step.effects.any { it is Effect.PauseTask || it == Effect.CancelTask })
        assertEquals(TaskPhase.HANDED_OVER, step.state.taskPhase)
        assertEquals(0, calls.ended)
    }

    @Test
    fun anActionOfAnEarlierTaskNeverCounts() {
        backstop.acted()
        backstop.forget()
        now = 1_000

        assertEquals(CallBackstop.Verdict.PAUSE, backstop.onPause(PauseCause.OUTGOING_CALL))
    }

    @Test
    fun aCallHandOverEndsByItselfOnlyWhenTheOwnerPlacesTheCall() = runTest {
        val states = MutableStateFlow(running)
        val incoming = async { awaitOutgoingCall(states) }
        runCurrent()
        states.value = running.copy(mode = Mode.RINGING)
        runCurrent()
        states.value = running.copy(mode = Mode.IN_CALL)
        runCurrent()
        assertFalse(incoming.isCompleted)
        incoming.cancel()

        val placed = async { awaitOutgoingCall(states.apply { value = running }) }
        runCurrent()
        states.value = running.copy(mode = Mode.IN_CALL)
        runCurrent()
        assertTrue(placed.isCompleted)
    }

    @Test
    fun aCallThatTheOwnerPlacesAfterAnIncomingCallEndedStillEndsTheHandOver() = runTest {
        val states = MutableStateFlow(running)
        val placed = async { awaitOutgoingCall(states) }
        runCurrent()
        states.value = running.copy(mode = Mode.RINGING)
        runCurrent()
        states.value = running.copy(mode = Mode.IN_CALL)
        runCurrent()
        states.value = running
        runCurrent()
        assertFalse(placed.isCompleted)

        states.value = running.copy(mode = Mode.IN_CALL)
        runCurrent()
        assertTrue(placed.isCompleted)
    }

    private class FakeCalls : CallApi {
        var allowed = true
        var ended = 0

        override fun allowed(): Boolean = allowed

        override fun answer() = Unit

        override fun end(): Boolean {
            ended++
            return true
        }

        override fun active(): Boolean = false

        override fun showInCallScreen() = Unit
    }
}
