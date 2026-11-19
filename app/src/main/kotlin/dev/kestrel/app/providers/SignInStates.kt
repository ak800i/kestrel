package dev.kestrel.app.providers

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The state of the add flow's sign-in, which the sign-in's job changes on another thread than the page (design D9 and
 * D10). Each sign-in gets a ticket from [begin], and its job changes the state only with that ticket, so that a sign-in
 * that was cancelled or replaced never overwrites Idle or a newer sign-in's state, even when its job had already
 * finished when the cancel came.
 */
internal class SignInStates {
    private val state = MutableStateFlow<SignInState>(SignInState.Idle)
    val flow: StateFlow<SignInState> = state.asStateFlow()
    val value: SignInState get() = state.value

    // Guarded by this; a new sign-in or an end of the current one makes every earlier ticket stale.
    private var ticket = 0L

    /** Starts a new sign-in with [first], which ends any earlier one; its job changes the state with the returned ticket. */
    @Synchronized
    fun begin(first: SignInState): Long {
        state.value = first
        return ++ticket
    }

    /** Changes the state of [ticket]'s sign-in, while it is the current one; false when a cancel or a newer sign-in came. */
    @Synchronized
    fun set(ticket: Long, change: (SignInState) -> SignInState): Boolean {
        if (ticket != this.ticket) return false
        state.value = change(state.value)
        return true
    }

    /**
     * Ends the current sign-in with what [change] makes of its state, or leaves it when [change] gives null; returns the
     * state before the change, or null when nothing changed.
     */
    @Synchronized
    fun end(change: (SignInState) -> SignInState?): SignInState? {
        val before = state.value
        val after = change(before) ?: return null
        ticket++
        state.value = after
        return before
    }
}
