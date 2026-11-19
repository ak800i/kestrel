package dev.kestrel.app.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only the current sign-in changes the add flow's sign-in state (design D9 and D10). */
class SignInStatesTest {
    private val waiting = SignInState.Waiting("xai", "xai-oauth")
    private val failed = SignInState.Failed("xai", "xai-oauth", null, null)

    @Test
    fun aCancelledSignInNeverOverwritesIdle() {
        val states = SignInStates()
        val ticket = states.begin(waiting)

        assertEquals(waiting, states.end { SignInState.Idle })
        // The job had already finished its flow when the cancel came.
        assertFalse(states.set(ticket) { failed })

        assertEquals(SignInState.Idle, states.value)
    }

    @Test
    fun aReplacedSignInNeverOverwritesTheNewOneEvenForTheSameChoice() {
        val states = SignInStates()
        val old = states.begin(waiting)
        val new = states.begin(waiting)

        assertFalse(states.set(old) { failed })
        assertEquals(waiting, states.value)
        assertTrue(states.set(new) { failed })
        assertEquals(failed, states.value)
    }

    @Test
    fun anEndThatChangesNothingKeepsTheCurrentSignIn() {
        val states = SignInStates()
        val ticket = states.begin(waiting)

        assertNull(states.end { if (it is SignInState.Succeeded) SignInState.Idle else null })

        assertTrue(states.set(ticket) { failed })
        assertEquals(failed, states.flow.value)
    }
}
