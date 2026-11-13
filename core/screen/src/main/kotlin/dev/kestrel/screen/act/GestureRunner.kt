package dev.kestrel.screen.act

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** What became of a gesture that Kestrel dispatched. */
enum class GestureOutcome { COMPLETED, CANCELLED, REJECTED }

/** Runs Kestrel's own gestures one at a time (design spec, section 9.3). */
class GestureRunner(private val service: AccessibilityService) {
    private val mutex = Mutex()

    suspend fun perform(gesture: GestureDescription): GestureOutcome = mutex.withLock { dispatch(gesture) }

    private suspend fun dispatch(gesture: GestureDescription): GestureOutcome = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) = continuation.resume(GestureOutcome.COMPLETED)

                override fun onCancelled(gestureDescription: GestureDescription) = continuation.resume(GestureOutcome.CANCELLED)
            }
            if (!service.dispatchGesture(gesture, callback, null)) continuation.resume(GestureOutcome.REJECTED)
        }
    }
}
