package dev.kestrel.screen.read

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Remembers when the screen last changed, so that Kestrel can wait until it is quiet (design spec, sections 9.2
 * and 9.3). [clock] counts milliseconds and never jumps, like `SystemClock.uptimeMillis`.
 */
class ScreenChangeTracker(private val clock: () -> Long) {
    private val versions = MutableStateFlow(0L)

    @Volatile
    private var lastChangeAt = clock()

    // When an event of another app's window last came, or null before the first.
    @Volatile
    private var windowChangedAt: Long? = null

    /** Counts the changes so far. A snapshot built at one version is stale at any later version. */
    val version: StateFlow<Long> = versions.asStateFlow()

    /** The time on this tracker's clock, for [awaitQuiet]'s `since`. */
    fun now(): Long = clock()

    /**
     * Notes a change of the screen. With [window], the change is an event of another app's window, which may still be
     * sliding into place for a while, see [windowMayMove].
     */
    fun onScreenChanged(window: Boolean = false) {
        val now = clock()
        if (window) windowChangedAt = now
        lastChangeAt = now
        versions.update { it + 1 }
    }

    /**
     * Whether a window may still be moving into place: an event of another app's window came less than
     * [SETTLE_MAX_MILLIS] ago (design spec, section 9.2).
     */
    fun windowMayMove(): Boolean = windowChangedAt?.let { clock() - it < SETTLE_MAX_MILLIS } ?: false

    /**
     * Waits until the screen has had no change for [quietMillis], counted from [since] at the earliest, and
     * returns true. Returns false when that takes longer than [maxWaitMillis]. [since] must come from [now], because
     * only this tracker's clock fits its times.
     */
    suspend fun awaitQuiet(quietMillis: Long, since: Long = Long.MIN_VALUE, maxWaitMillis: Long = MAX_WAIT_MILLIS): Boolean {
        val deadline = clock() + maxWaitMillis
        while (true) {
            val now = clock()
            val quietFor = now - maxOf(lastChangeAt, since)
            if (quietFor >= quietMillis) return true
            if (now >= deadline) return false
            delay(minOf(quietMillis - quietFor, deadline - now))
        }
    }

    companion object {
        /** How long the screen must be quiet before Kestrel builds a new snapshot. */
        const val SNAPSHOT_QUIET_MILLIS = 150L

        /** How long the screen must be quiet after an action. */
        const val ACTION_QUIET_MILLIS = 200L

        /** The longest wait for a quiet screen. */
        const val MAX_WAIT_MILLIS = 3_000L

        /**
         * How long after an event of another app's window Kestrel checks that a new snapshot's window has stopped moving:
         * on kestrel-api37, an app's window slid into place for 0.5 to 0.9 s after it opened.
         */
        const val SETTLE_MAX_MILLIS = 1_500L

        /** The time between a snapshot and the second read that checks that its windows have stopped moving. */
        const val SETTLE_CHECK_MILLIS = 100L
    }
}
