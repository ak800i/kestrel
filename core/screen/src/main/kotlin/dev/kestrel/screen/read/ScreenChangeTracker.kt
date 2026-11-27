package dev.kestrel.screen.read

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Remembers when the screen last changed, so that Kestrel can wait until it is quiet (design spec, sections 9.2
 * and 9.3), within a [QuietBudget]. [clock] counts milliseconds and never jumps, like `SystemClock.uptimeMillis`.
 */
class ScreenChangeTracker(private val clock: () -> Long) {
    private val versions = MutableStateFlow(0L)

    @Volatile
    private var lastChangeAt = clock()

    // An event of another app's window: when it came, and how many window events came up to it.
    private class WindowEvent(val at: Long, val count: Long)

    // The newest event of each window that may still be moving into place, by window ID.
    private val windowEvents = ConcurrentHashMap<Int, WindowEvent>()
    private val windowEventCount = AtomicLong()

    /** Counts the changes so far. A snapshot built at one version is stale at any later version. */
    val version: StateFlow<Long> = versions.asStateFlow()

    /** The time on this tracker's clock, for [awaitQuiet]'s `since`. */
    fun now(): Long = clock()

    /**
     * Notes a change of the screen. A [window] ID says that the change is an event of that window of another app,
     * which may still be sliding into place for a while, see [movingWindows].
     */
    fun onScreenChanged(window: Int? = null) {
        val now = clock()
        if (window != null) {
            windowEvents.values.removeIf { now - it.at >= SETTLE_MAX_MILLIS }
            windowEvents[window] = WindowEvent(now, windowEventCount.incrementAndGet())
        }
        lastChangeAt = now
        versions.update { it + 1 }
    }

    /**
     * The IDs of the windows that may still be moving into place: those that an event named less than
     * [SETTLE_MAX_MILLIS] ago, unless a snapshot built after that event has passed the place check since, see [settled]
     * (design spec, section 9.2). Empty when no window may move.
     */
    fun movingWindows(): Set<Int> {
        val now = clock()
        return windowEvents.filterValues { now - it.at < SETTLE_MAX_MILLIS }.keys
    }

    /** How many window events have come so far, for [settled]. */
    fun windowEventCount(): Long = windowEventCount.get()

    /**
     * Notes that a snapshot built after the first [count] window events has passed the place check, so that the windows
     * of those events count as in place, until another event names them.
     */
    fun settled(count: Long) {
        windowEvents.values.removeIf { it.count <= count }
    }

    /**
     * Waits until the screen has had no change for [quietMillis], counted from [since] at the earliest, and
     * returns true. Returns false when the context's [QuietBudget], or a budget of this wait's own outside a command, runs
     * out first; the time that the wait took comes off that budget. [since] must come from [now], because only this
     * tracker's clock fits its times.
     */
    suspend fun awaitQuiet(quietMillis: Long, since: Long = Long.MIN_VALUE): Boolean {
        val budget = currentCoroutineContext()[QuietBudget] ?: QuietBudget()
        val started = clock()
        val deadline = started + budget.remainingMillis
        try {
            while (true) {
                val now = clock()
                val quietFor = now - maxOf(lastChangeAt, since)
                if (quietFor >= quietMillis) return true
                if (now >= deadline) return false
                delay(minOf(quietMillis - quietFor, deadline - now))
            }
        } finally {
            budget.spend(clock() - started)
        }
    }

    companion object {
        /** How long the screen must be quiet before Kestrel builds a new snapshot. */
        const val SNAPSHOT_QUIET_MILLIS = 150L

        /** How long the screen must be quiet after an action. */
        const val ACTION_QUIET_MILLIS = 200L

        /**
         * How long after an event of another app's window Kestrel checks that a new snapshot's window has stopped moving:
         * on kestrel-api37, an app's window slid into place for 0.5 to 0.9 s after it opened.
         */
        const val SETTLE_MAX_MILLIS = 1_500L

        /** The time between a snapshot and the second read that checks that its windows have stopped moving. */
        const val SETTLE_CHECK_MILLIS = 100L
    }
}
