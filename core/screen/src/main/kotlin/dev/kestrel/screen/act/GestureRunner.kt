package dev.kestrel.screen.act

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.os.RemoteException
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
     * When the caller cancels it, Android still runs the part that it has started, so Kestrel still waits for that part's
     * answer, and only then throws the cancellation, without starting another part: the caller's job completes only once
     * the finger has lifted, so that nothing new acts on the screen before (design spec, section 7.3).
     *
     * [press] learns the moment that the first part goes to Android, in this runner's turn ([PressDispatch.dispatching]),
     * and whether Android refused it or its hand-over failed ([PressDispatch.notDispatched]); a gesture that is cancelled
     * while it waits for its turn tells it nothing.
     */
    suspend fun perform(vararg parts: GestureDescription, press: PressDispatch? = null): GestureOutcome = mutex.withLock {
        require(parts.isNotEmpty()) { "A gesture needs at least one part" }
        busyUntil = Long.MAX_VALUE
        var plannedEnd = 0L
        try {
            inOrder(parts.asList(), started = { plannedEnd = SystemClock.uptimeMillis() + it.durationMillis() }) { part ->
                dispatch(part, press.takeIf { part === parts.first() })
            }
        } finally {
            busyUntil = maxOf(SystemClock.uptimeMillis(), plannedEnd) + SETTLE_MILLIS
        }
    }

    /** Waits for Android's answer, but not for ever: a lost answer would otherwise keep the lock for good. */
    private suspend fun dispatch(gesture: GestureDescription, press: PressDispatch?): GestureOutcome = withContext(Dispatchers.Main) {
        withTimeoutOrNull(gesture.durationMillis() + ANSWER_MARGIN_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                val callback = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) = continuation.resume(GestureOutcome.COMPLETED)

                    override fun onCancelled(gestureDescription: GestureDescription) = continuation.resume(GestureOutcome.CANCELLED)
                }
                val accepted = handedOver(press) {
                    try {
                        service.dispatchGesture(gesture, callback, null)
                    } catch (e: RuntimeException) {
                        // A failed binder call reaches the app as a RuntimeException around a RemoteException.
                        if (e.cause !is RemoteException) throw e
                        false
                    }
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

        /**
         * Hands a gesture to Android through [dispatch], which answers whether Android took it, and tells [press] right
         * before, and again when Android refused it or the hand-over threw, which it then throws on, so that only a gesture
         * that Android took keeps what [press] did.
         */
        internal fun handedOver(press: PressDispatch?, dispatch: () -> Boolean): Boolean {
            press?.dispatching()
            val accepted = try {
                dispatch()
            } catch (e: Throwable) {
                press?.notDispatched()
                throw e
            }
            if (!accepted) press?.notDispatched()
            return accepted
        }

        /**
         * Calls [started] and then [dispatch] for each of [parts] in order, and stops at the first part that does not
         * complete. The wait for a started part's answer is not cancelled, because Android runs that part to its end, and
         * [dispatch] already gives up after the part's planned end and a margin; a cancelled caller then starts no
         * further part and gets the cancellation.
         */
        internal suspend fun <T> inOrder(parts: List<T>, started: (T) -> Unit, dispatch: suspend (T) -> GestureOutcome): GestureOutcome {
            var outcome = GestureOutcome.COMPLETED
            for (part in parts) {
                currentCoroutineContext().ensureActive()
                started(part)
                outcome = withContext(NonCancellable) { dispatch(part) }
                currentCoroutineContext().ensureActive()
                if (outcome != GestureOutcome.COMPLETED) break
            }
            return outcome
        }
    }
}
