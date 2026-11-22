package dev.kestrel.screen.overlay

import android.os.SystemClock
import android.view.View
import dev.kestrel.screen.model.IntRect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Kestrel's overlays while a screenshot of the whole display is taken (design D20 of sub-project 5): Android 13's
 * capture holds them, so they are made transparent first. [close] shows them again; use it on the main thread. [bounds]
 * are the screen bounds of the overlays that did not cover the whole screen, such as the feedback bar, which the capture
 * paints black in case one of them was drawn again in the meantime.
 */
class HiddenOverlays internal constructor(private val views: List<Pair<View, Float>>, val bounds: List<IntRect>) : AutoCloseable {
    override fun close() {
        // An overlay that changed its own transparency meanwhile, such as the feedback bar while it fades, keeps it.
        for ((view, alpha) in views) if (view.alpha == 0f) view.alpha = alpha
    }

    override fun toString(): String = "HiddenOverlays(${views.size})"

    internal companion object {
        const val TIMEOUT_MILLIS = 500L
        const val MIN_MILLIS = 50L

        /**
         * Makes every overlay of [host] transparent and returns once Android has committed a frame of each one and at
         * least 50 ms have passed, or null, with the overlays shown again, when a frame did not come within 500 ms.
         */
        suspend fun hide(host: OverlayHost): HiddenOverlays? {
            val started = SystemClock.uptimeMillis()
            val screen = host.screenBounds()
            val views = host.visibleViews.filter { it.isAttachedToWindow && it.alpha > 0f }
            val bounds = views.map { view ->
                val at = IntArray(2).also(view::getLocationOnScreen)
                IntRect(at[0], at[1], at[0] + view.width, at[1] + view.height)
            }.filter { it != screen && !it.isEmpty }
            val hidden = HiddenOverlays(views.map { it to it.alpha }, bounds)
            views.forEach { it.alpha = 0f }
            try {
                val drawn = withTimeoutOrNull(TIMEOUT_MILLIS) { coroutineScope { views.map { async { committed(it) } }.awaitAll() } }
                if (drawn == null) {
                    hidden.close()
                    return null
                }
                val left = MIN_MILLIS - (SystemClock.uptimeMillis() - started)
                if (left > 0) delay(left)
            } catch (e: CancellationException) {
                // A cancelled capture never receives the handle, so the overlays are shown again here (on the main thread).
                hidden.close()
                throw e
            }
            return hidden
        }

        private suspend fun committed(view: View) = suspendCancellableCoroutine { continuation ->
            view.viewTreeObserver.registerFrameCommitCallback { if (continuation.isActive) continuation.resume(Unit) }
            view.invalidate()
        }
    }
}
