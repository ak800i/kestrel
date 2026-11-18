package dev.kestrel.screen.read

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * Checks whether the windows of a snapshot are still where it saw them, so that [SnapshotCache] never hands out a
 * snapshot of a window that was still sliding into place (design spec, section 9.2).
 */
interface PlaceCheck<T> {
    /** Whether [value]'s windows and a few of its elements are still where [value] saw them, read past any cache. */
    suspend fun inPlace(value: T): Boolean

    /** Drops what earlier reads cached, so that the next snapshot reads the windows anew. */
    fun forget()
}

/**
 * Keeps the last snapshot until the screen changes. After a change, it builds a new snapshot once the screen
 * has been quiet for [ScreenChangeTracker.SNAPSHOT_QUIET_MILLIS], or after [ScreenChangeTracker.MAX_WAIT_MILLIS] on a
 * screen that never goes quiet, such as a stopwatch (design spec, section 9.2). After an event of another app's window,
 * which may still slide into place without a further event, [places] checks each new snapshot
 * [ScreenChangeTracker.SETTLE_CHECK_MILLIS] later, and the snapshot is built again until the check finds it in place,
 * or until [ScreenChangeTracker.SETTLE_MAX_MILLIS] after the event, when the newest snapshot counts.
 */
class SnapshotCache<T : Any>(
    private val tracker: ScreenChangeTracker,
    private val places: PlaceCheck<T>? = null,
    private val build: suspend () -> T,
) {
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
                var version = tracker.version.value
                var invalidated = invalidations.get()
                var value = build()
                while (places != null && tracker.windowMayMove()) {
                    delay(ScreenChangeTracker.SETTLE_CHECK_MILLIS)
                    if (places.inPlace(value)) break
                    places.forget()
                    version = tracker.version.value
                    invalidated = invalidations.get()
                    value = build()
                }
                entry = Entry(value, version, invalidated)
                value
            }
        }
    }

    /**
     * The newest snapshot that was built, or null before the first build. It never waits and never builds, so it may be
     * older than the screen: the recogniser's boost words read it before each utterance (design spec, section 8.3), and
     * `ScreenSession.keepFresh` keeps it current while Kestrel listens.
     */
    fun latest(): T? = entry?.value

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
