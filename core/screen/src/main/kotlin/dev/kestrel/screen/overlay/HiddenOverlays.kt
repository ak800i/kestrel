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
 * capture holds them, so they are made transparent first, and an overlay that [OverlayHost.show] shows or changes until
 * [close] stays transparent too. [close] shows them again; use it and [intact] on the main thread. [bounds] are the screen
 * bounds of the overlays that did not cover the whole screen when the hide began, such as the feedback bar, which the
 * capture paints black in case one of them was drawn again in the meantime.
 */
class HiddenOverlays private constructor(private val host: OverlayHost, private val screen: IntRect) : AutoCloseable {
    // An overlay that this handle made transparent, the alpha that it gets back, and the bounds that the capture paints
    // black, or null when it must stay transparent.
    private class Hidden(val view: View, var alpha: Float, val mask: IntRect?)

    private val hidden = mutableListOf<Hidden>()

    val bounds: List<IntRect> get() = hidden.mapNotNull { it.mask }

    /** Makes [view] transparent until [close], and with [masked] records its bounds now for the capture's black. */
    internal fun hide(view: View, masked: Boolean = false) {
        val known = hidden.firstOrNull { it.view === view }
        if (known == null) {
            val mask = if (masked) boundsOf(view).takeIf { it != screen && !it.isEmpty } else null
            hidden += Hidden(view, view.alpha, mask)
        } else if (view.alpha > 0f) {
            known.alpha = view.alpha
        }
        view.alpha = 0f
    }

    /**
     * Whether a screenshot taken since the hide can hold none of Kestrel's overlays: every overlay that Android draws is
     * transparent, or lies within the bounds that the capture paints black.
     */
    fun intact(): Boolean = host.visibleViews.all { view ->
        if (!view.isAttachedToWindow || view.alpha == 0f) return@all true
        val mask = hidden.firstOrNull { it.view === view }?.mask
        mask != null && mask.contains(boundsOf(view))
    }

    override fun close() {
        if (host.hiding === this) host.hiding = null
        // An overlay that changed its own transparency meanwhile, such as the feedback bar while it fades, keeps it.
        for (it in hidden) if (it.view.alpha == 0f) it.view.alpha = it.alpha
    }

    override fun toString(): String = "HiddenOverlays(${hidden.size})"

    internal companion object {
        const val TIMEOUT_MILLIS = 500L
        const val MIN_MILLIS = 50L

        /**
         * Makes every overlay of [host] transparent and returns once Android has committed a frame of each one on the
         * screen and at least 50 ms have passed, or null, with the overlays shown again, when a frame did not come within
         * 500 ms.
         */
        suspend fun hide(host: OverlayHost): HiddenOverlays? {
            val started = SystemClock.uptimeMillis()
            val hidden = HiddenOverlays(host, host.screenBounds())
            // An overlay that Android has not attached yet is drawn transparent from its first frame on.
            val views = host.visibleViews.filter { it.alpha > 0f }
            for (view in views) hidden.hide(view, masked = view.isAttachedToWindow)
            host.hiding = hidden
            try {
                val attached = views.filter { it.isAttachedToWindow }
                val drawn = withTimeoutOrNull(TIMEOUT_MILLIS) { coroutineScope { attached.map { async { committed(it) } }.awaitAll() } }
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

        private fun boundsOf(view: View): IntRect {
            val at = IntArray(2).also(view::getLocationOnScreen)
            return IntRect(at[0], at[1], at[0] + view.width, at[1] + view.height)
        }

        private suspend fun committed(view: View) = suspendCancellableCoroutine { continuation ->
            view.viewTreeObserver.registerFrameCommitCallback { if (continuation.isActive) continuation.resume(Unit) }
            view.invalidate()
        }
    }
}