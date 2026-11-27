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
    /**
     * Whether the [windows] that may be moving are still where [value] saw them: each one's bounds in Android's window
     * list, which Android may serve from its cache, and a few of each one's nodes, read again past the accessibility
     * cache. A window that [value] does not have counts as in place.
     */
    suspend fun inPlace(value: T, windows: Set<Int>): Boolean

    /** Drops what earlier reads cached, so that the next snapshot reads the windows anew. */
    fun forget()
}

/**
 * Keeps the last snapshot until the screen changes. After a change, it builds a new snapshot once the screen
 * has been quiet for [ScreenChangeTracker.SNAPSHOT_QUIET_MILLIS], or once the caller's [QuietBudget] has run out on a
 * screen that never goes quiet, such as a stopwatch (design spec, section 9.2). While windows of other apps may still
 * slide into place after their events ([ScreenChangeTracker.movingWindows]), which may end without a further event,
 * [places] checks each new snapshot [ScreenChangeTracker.SETTLE_CHECK_MILLIS] later, and the snapshot is built again
 * until the check finds those windows in place. The checks of one call end [ScreenChangeTracker.SETTLE_MAX_MILLIS]
 * after its first snapshot, however many events come meanwhile, and the newest snapshot then counts; a build or a check
 * that is under way at that time ends first, so one call can take that long plus one build.
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
                var events = tracker.windowEventCount()
                var value = build()
                // Set once, so that no later window event makes this call wait longer.
                val until = tracker.now() + ScreenChangeTracker.SETTLE_MAX_MILLIS
                while (places != null && tracker.now() + ScreenChangeTracker.SETTLE_CHECK_MILLIS <= until) {
                    if (tracker.movingWindows().isEmpty()) break
                    delay(ScreenChangeTracker.SETTLE_CHECK_MILLIS)
                    val moving = tracker.movingWindows()
                    if (moving.isEmpty()) break
                    if (places.inPlace(value, moving)) {
                        tracker.settled(events)
                        break
                    }
                    // At the deadline, the newest snapshot counts.
                    if (tracker.now() >= until) break
                    places.forget()
                    version = tracker.version.value
                    invalidated = invalidations.get()
                    events = tracker.windowEventCount()
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
