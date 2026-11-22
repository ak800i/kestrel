package dev.kestrel.app.agent

import dev.kestrel.app.listen.CallApi
import dev.kestrel.app.orchestrator.PauseCause

/**
 * The backstop against a call that an action of the agent placed (design D13 of sub-project 5; review B3-I1). The agent
 * hands every call over, so no action of its own should start one, and this backstop needs no list of apps: when a pause
 * of the Orchestrator comes from a call that became active without ringing ([PauseCause.OUTGOING_CALL]) within
 * [WINDOW_MILLIS] after the agent's last action, the task stops at once ([Verdict.STOP]) and, with Android's permission
 * for calls, Kestrel ends that call. A call that rang is an incoming call: Kestrel never ends it, and the task only pauses
 * (section 14.4). The Orchestrator pauses only a running task, never a hand-over or a paused task, so a call that the
 * owner places himself then never reaches the backstop. Android reports the call state only with `READ_PHONE_STATE`, so
 * without it nothing reaches the backstop either. [clock] is monotonic, such as `SystemClock.elapsedRealtime`.
 */
internal class CallBackstop(private val calls: CallApi, private val clock: () -> Long) {
    enum class Verdict { PAUSE, STOP }

    @Volatile
    private var lastAction: Long? = null

    /** The agent performed an action on the screen or sent an intent just now. */
    fun acted() {
        lastAction = clock()
    }

    /** A new task starts: an action of an earlier task never counts. */
    fun forget() {
        lastAction = null
    }

    /** What the pause of the running task for [cause] means. */
    fun onPause(cause: PauseCause): Verdict {
        val last = lastAction ?: return Verdict.PAUSE
        if (cause != PauseCause.OUTGOING_CALL || clock() - last > WINDOW_MILLIS) return Verdict.PAUSE
        try {
            if (calls.allowed()) calls.end()
        } catch (e: SecurityException) {
            // The owner took the permission back meanwhile; the task stops all the same.
        }
        return Verdict.STOP
    }

    override fun toString(): String = "CallBackstop(acted=${lastAction != null})"

    companion object {
        const val WINDOW_MILLIS = 5_000L
    }
}
