package dev.kestrel.app.conversation

import dev.kestrel.app.orchestrator.CallState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hand-overs that end by themselves (design D9 and D15 of sub-project 5), with listening on or off. */
@OptIn(ExperimentalCoroutinesApi::class)
class OwnerSupportTest {
    @Test
    fun theAccessHandOverEndsByItselfOnlyOnceAndroidsPageHasLeftTheFront() = runTest {
        val connected = MutableStateFlow(false)
        var front: String? = SETTINGS
        val given = async { awaitAccessGiven(connected, SETTINGS) { front } }
        runCurrent()

        connected.value = true
        advanceTimeBy(1_000)
        assertFalse(given.isCompleted)

        front = "com.android.launcher3"
        advanceTimeBy(1_000)
        assertTrue(given.isCompleted)
    }

    @Test
    fun aCallHandOverEndsByItselfOnlyWhenTheOwnerPlacesTheCall() = runTest {
        val calls = MutableStateFlow(CallState.IDLE)
        val incoming = async { awaitOutgoingCall(calls) }
        runCurrent()
        calls.value = CallState.RINGING
        runCurrent()
        calls.value = CallState.ACTIVE
        runCurrent()
        assertFalse(incoming.isCompleted)
        incoming.cancel()

        calls.value = CallState.IDLE
        val placed = async { awaitOutgoingCall(calls) }
        runCurrent()
        calls.value = CallState.ACTIVE
        runCurrent()
        assertTrue(placed.isCompleted)
    }

    @Test
    fun aCallThatTheOwnerPlacesAfterAnIncomingCallEndedStillEndsTheHandOver() = runTest {
        val calls = MutableStateFlow(CallState.IDLE)
        val placed = async { awaitOutgoingCall(calls) }
        runCurrent()
        calls.value = CallState.RINGING
        runCurrent()
        calls.value = CallState.ACTIVE
        runCurrent()
        calls.value = CallState.IDLE
        runCurrent()
        assertFalse(placed.isCompleted)

        calls.value = CallState.ACTIVE
        runCurrent()
        assertTrue(placed.isCompleted)
    }

    private companion object {
        const val SETTINGS = "com.android.settings"
    }
}
