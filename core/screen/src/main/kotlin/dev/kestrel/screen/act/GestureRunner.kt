package dev.kestrel.screen.act

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.os.RemoteException
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** What became of a gesture that Kestrel dispatched. [TIMED_OUT]: Android did not report its end in time. */
enum class GestureOutcome { COMPLETED, CANCELLED, REJECTED, TIMED_OUT }

/**
 * Runs Kestrel's own gestures one at a time (design spec, section 9.3), and remembers when they run, so that the
 * touch watcher can tell them apart from the owner's touches (section 14.1).
 */
class GestureRunner(private val service: AccessibilityService) {
    private val mutex = Mutex()

    @Volatile
    private var busyUntil = 0L

    /** Whether one of Kestrel's gestures is running, or ended less than [SETTLE_MILLIS] ago. */
    fun isRunning(): Boolean = SystemClock.uptimeMillis() < busyUntil

    /**
     * Runs the [parts] of one gesture in order, such as the hold and the move of a drag, and stops at a failed part.
     * When the caller cancels it, Android still runs the part that it has started, so the gesture counts as running
     * until that part's planned end.
     */
    suspend fun perform(vararg parts: GestureDescription): GestureOutcome = mutex.withLock {
        require(parts.isNotEmpty()) { "A gesture needs at least one part" }
        busyUntil = Long.MAX_VALUE
        var plannedEnd = 0L
        try {
            var outcome = GestureOutcome.COMPLETED
            for (part in parts) {
                plannedEnd = SystemClock.uptimeMillis() + part.durationMillis()
                outcome = dispatch(part)
                if (outcome != GestureOutcome.COMPLETED) break
            }
            outcome
        } finally {
            busyUntil = maxOf(SystemClock.uptimeMillis(), plannedEnd) + SETTLE_MILLIS
        }
    }

    /** Waits for Android's answer, but not for ever: a lost answer would otherwise keep the lock for good. */
    private suspend fun dispatch(gesture: GestureDescription): GestureOutcome = withContext(Dispatchers.Main) {
        withTimeoutOrNull(gesture.durationMillis() + ANSWER_MARGIN_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                val callback = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) = continuation.resume(GestureOutcome.COMPLETED)

                    override fun onCancelled(gestureDescription: GestureDescription) = continuation.resume(GestureOutcome.CANCELLED)
                }
                val accepted = try {
                    service.dispatchGesture(gesture, callback, null)
                } catch (e: RuntimeException) {
                    // A failed binder call reaches the app as a RuntimeException around a RemoteException.
                    if (e.cause !is RemoteException) throw e
                    false
                }
                if (!accepted) continuation.resume(GestureOutcome.REJECTED)
            }
        } ?: GestureOutcome.TIMED_OUT
    }

    private fun GestureDescription.durationMillis(): Long =
        (0 until strokeCount).maxOf { getStroke(it).let { stroke -> stroke.startTime + stroke.duration } }

    companion object {
        /** How long after one of Kestrel's gestures its touches may still arrive at the touch watcher. */
        const val SETTLE_MILLIS = 100L

        /** How long after a gesture's planned end Kestrel still waits for Android's answer. */
        const val ANSWER_MARGIN_MILLIS = 2_000L
    }
}
