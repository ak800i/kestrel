package dev.kestrel.screen.read

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the last snapshot until the screen changes. After a change, it builds a new snapshot once the screen
 * has been quiet for [ScreenChangeTracker.SNAPSHOT_QUIET_MILLIS] (design spec, section 9.2).
 */
class SnapshotCache<T : Any>(private val tracker: ScreenChangeTracker, private val build: suspend () -> T) {
    private val mutex = Mutex()
    private var cached: T? = null

    @Volatile
    private var cachedVersion = -1L

    suspend fun current(): T = mutex.withLock {
        cached?.takeIf { cachedVersion == tracker.version.value }?.let { return it }
        tracker.awaitQuiet(ScreenChangeTracker.SNAPSHOT_QUIET_MILLIS)
        val version = tracker.version.value
        build().also {
            cached = it
            cachedVersion = version
        }
    }

    /** Makes the next [current] call build a new snapshot, for example before an action is retried. */
    fun invalidate() {
        cachedVersion = -1L
    }
}
