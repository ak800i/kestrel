package dev.kestrel.app.agent

import dev.kestrel.app.listen.CallApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The backstop against a call that the agent placed (design D13 of sub-project 5; plan 7D). */
class CallBackstopTest {
    private var now = 0L
    private val calls = FakeCalls()
    private val backstop = CallBackstop(calls) { now }

    @Test
    fun aCallWithinFiveSecondsOfTheAgentsLastActionIsTheAgents() {
        assertFalse(backstop.stops())

        backstop.acted()
        now = 5_000
        assertTrue(backstop.stops())
        now = 5_001
        assertFalse(backstop.stops())
    }

    @Test
    fun anActionOfAnEarlierRoundNeverCounts() {
        backstop.acted()
        backstop.forget()
        now = 1_000

        assertFalse(backstop.stops())
    }

    @Test
    fun kestrelEndsTheAgentsCallOnlyWithThePermission() {
        backstop.endCall()
        assertEquals(1, calls.ended)

        calls.allowed = false
        backstop.endCall()
        assertEquals(1, calls.ended)

        // The owner took the permission back meanwhile: the goal stops all the same.
        calls.allowed = true
        calls.refused = true
        backstop.endCall()
        assertEquals(1, calls.ended)
    }

    private class FakeCalls : CallApi {
        var allowed = true
        var refused = false
        var ended = 0

        override fun allowed(): Boolean = allowed

        override fun answer() = Unit

        override fun end(): Boolean {
            if (refused) throw SecurityException("No permission for calls")
            ended++
            return true
        }

        override fun active(): Boolean = false

        override fun showInCallScreen() = Unit
    }
}
