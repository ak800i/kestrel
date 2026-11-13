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

    /** Counts the changes so far. A snapshot built at one version is stale at any later version. */
    val version: StateFlow<Long> = versions.asStateFlow()

    fun onScreenChanged() {
        lastChangeAt = clock()
        versions.update { it + 1 }
    }

    /**
     * Waits until the screen has had no change for [quietMillis], counted from [since] at the earliest, and
     * returns true. Returns false when that takes longer than [maxWaitMillis].
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
    }
}
