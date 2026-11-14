package dev.kestrel.screen.read

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps the last snapshot until the screen changes. After a change, it builds a new snapshot once the screen
 * has been quiet for [ScreenChangeTracker.SNAPSHOT_QUIET_MILLIS], or after [ScreenChangeTracker.MAX_WAIT_MILLIS] on a
 * screen that never goes quiet, such as a stopwatch (design spec, section 9.2).
 */
class SnapshotCache<T : Any>(private val tracker: ScreenChangeTracker, private val build: suspend () -> T) {
    /** A snapshot with the tracker's version and the number of [invalidate] calls when its build started. */
    private class Entry<T>(val value: T, val version: Long, val invalidations: Long)

    private val mutex = Mutex()

    @Volatile
    private var entry: Entry<T>? = null

    // Counts the calls of invalidate(), so that a snapshot whose build was already running is not kept.
    private val invalidations = AtomicLong()

    suspend fun current(): T {
        fresh()?.let { return it }
        // Callers wait for the quiet screen side by side, outside the lock, instead of one after another.
        tracker.awaitQuiet(ScreenChangeTracker.SNAPSHOT_QUIET_MILLIS)
        return mutex.withLock {
            // Another caller may have built a snapshot of the current screen meanwhile.
            fresh() ?: run {
                val version = tracker.version.value
                val invalidated = invalidations.get()
                build().also { entry = Entry(it, version, invalidated) }
            }
        }
    }

    /**
     * Makes the next [current] call build a new snapshot, for example before an action is retried. This also holds
     * when another caller is building a snapshot at the same moment.
     */
    fun invalidate() {
        invalidations.incrementAndGet()
    }

    /** The cached snapshot, if the screen has not changed and nobody has called [invalidate] since its build started. */
    private fun fresh(): T? =
        entry?.takeIf { it.version == tracker.version.value && it.invalidations == invalidations.get() }?.value
}
