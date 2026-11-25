package dev.kestrel.app.agent

import dev.kestrel.app.listen.CallApi

/**
 * The backstop against a call that an action of the agent placed (design D13 of sub-project 5; review B3-I1). The agent
 * hands every call over, so no action of its own should start one, and this backstop needs no list of apps: when a call
 * becomes active without ringing while a goal acts or asks, and within [WINDOW_MILLIS] after the agent's last action
 * ([stops]), the conversation ends the goal at once and, with Android's permission for calls, Kestrel ends that call
 * ([endCall]). `ConversationSignals` decides which call it is (plan 7D): a call that rang is an incoming call, which
 * Kestrel never ends, and a call that the owner places during a hand-over or a pause never reaches the backstop. Android
 * reports the call state only with `READ_PHONE_STATE`, so without it nothing reaches the backstop either. [clock] is
 * monotonic, such as `SystemClock.elapsedRealtime`.
 */
internal class CallBackstop(private val calls: CallApi, private val clock: () -> Long) {
    @Volatile
    private var lastAction: Long? = null

    /** The agent performed an action on the screen or sent an intent just now. */
    fun acted() {
        lastAction = clock()
    }

    /** A new round starts: an action of an earlier round never counts. */
    fun forget() {
        lastAction = null
    }

    /** Whether a call that became active without ringing just now is the agent's: its last action was within the window. */
    fun stops(): Boolean {
        val last = lastAction ?: return false
        return clock() - last <= WINDOW_MILLIS
    }

    /**
     * Ends the call that [stops] found, with Android's permission for calls. These are binder calls, so the caller runs it
     * off Android's thread; it throws what [CallApi] throws, except a SecurityException.
     */
    fun endCall() {
        try {
            if (calls.allowed()) calls.end()
        } catch (e: SecurityException) {
            // The owner took the permission back meanwhile; the goal stops all the same.
        }
    }

    override fun toString(): String = "CallBackstop(acted=${lastAction != null})"

    companion object {
        const val WINDOW_MILLIS = 5_000L
    }
}
