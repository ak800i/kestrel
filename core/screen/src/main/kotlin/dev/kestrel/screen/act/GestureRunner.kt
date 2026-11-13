package dev.kestrel.screen.act

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** What became of a gesture that Kestrel dispatched. */
enum class GestureOutcome { COMPLETED, CANCELLED, REJECTED }

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

    /** Runs the [parts] of one gesture in order, such as the hold and the move of a drag, and stops at a failed part. */
    suspend fun perform(vararg parts: GestureDescription): GestureOutcome = mutex.withLock {
        busyUntil = Long.MAX_VALUE
        try {
            var outcome = GestureOutcome.COMPLETED
            for (part in parts) {
                outcome = dispatch(part)
                if (outcome != GestureOutcome.COMPLETED) break
            }
            outcome
        } finally {
            busyUntil = SystemClock.uptimeMillis() + SETTLE_MILLIS
        }
    }

    private suspend fun dispatch(gesture: GestureDescription): GestureOutcome = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) = continuation.resume(GestureOutcome.COMPLETED)

                override fun onCancelled(gestureDescription: GestureDescription) = continuation.resume(GestureOutcome.CANCELLED)
            }
            if (!service.dispatchGesture(gesture, callback, null)) continuation.resume(GestureOutcome.REJECTED)
        }
    }

    companion object {
        /** How long after one of Kestrel's gestures its touches may still arrive at the touch watcher. */
        const val SETTLE_MILLIS = 100L
    }
}
