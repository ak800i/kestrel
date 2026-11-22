package dev.kestrel.screen.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.WindowManager.LayoutParams
import dev.kestrel.screen.model.IntRect

/**
 * Shows Kestrel's overlay windows. They are accessibility overlays, so Kestrel needs no "display over other apps"
 * permission, and snapshots leave them out (design spec, sections 9.2 and 9.4). Use it on the main thread.
 */
class OverlayHost(private val service: AccessibilityService) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val shown = mutableSetOf<View>()
    private var closed = false

    val context: Context get() = service

    /** The overlays on the screen now, for a screenshot that hides them (design D20 of sub-project 5). */
    internal val visibleViews: List<View> get() = shown.toList()

    /** The overlays hidden for a screenshot of the display that runs now, or null. */
    internal var hiding: HiddenOverlays? = null

    /**
     * Shows [view], or updates its layout when it is on the screen already, and returns whether it is on the screen.
     * After [close], or once Android has disconnected the service, it shows nothing. It gives [view] the accessibility
     * delegate that names it [ROOT_CLASS] in its window events, in place of any delegate that [view] had, so an overlay's
     * root view must not set one of its own. While a screenshot of the display runs, [view] stays transparent until the
     * screenshot is taken.
     */
    fun show(view: View, params: LayoutParams): Boolean {
        if (closed) return false
        hiding?.hide(view)
        if (view in shown) {
            return try {
                windowManager.updateViewLayout(view, params)
                true
            } catch (e: IllegalArgumentException) {
                // Android has removed the window already, as it does when the service disconnects.
                shown -= view
                false
            }
        }
        view.accessibilityDelegate = RootDelegate
        return try {
            windowManager.addView(view, params)
            shown += view
            true
        } catch (e: WindowManager.BadTokenException) {
            // The service has disconnected, so Android no longer lets it add windows.
            false
        }
    }

    fun hide(view: View) {
        if (!shown.remove(view)) return
        // Remove the window even when Android has not attached its view yet, which it does only on the next frame.
        try {
            windowManager.removeView(view)
        } catch (e: IllegalArgumentException) {
            // Android has removed the window already, as it does when the service disconnects.
        }
    }

    /** The whole screen, system bars included, in screen pixels. */
    fun screenBounds(): IntRect = windowManager.maximumWindowMetrics.bounds.let { IntRect(it.left, it.top, it.right, it.bottom) }

    /** Removes every overlay for good. Later calls of [show] show nothing. */
    fun close() {
        closed = true
        shown.toList().forEach(::hide)
    }

    companion object {
        /**
         * The class name that the state events of Kestrel's overlay windows give, so that `WindowEvents` can tell them
         * from the events of other windows, Kestrel's settings app's among them (design spec, section 9.2).
         */
        const val ROOT_CLASS = "dev.kestrel.screen.overlay.Overlay"

        // Android sends a window's state events from its root view, with the root view's class name.
        private object RootDelegate : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityEvent(host: View, event: AccessibilityEvent) {
                super.onInitializeAccessibilityEvent(host, event)
                if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) event.className = ROOT_CLASS
            }
        }

        /**
         * A window over the whole screen, system bars included, that lets every touch through to the apps below.
         * The [title] names the window in Android's window list.
         */
        fun fullScreen(title: String): LayoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT,
            LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCHABLE or
                LayoutParams.FLAG_LAYOUT_IN_SCREEN or LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
            setTitle(title)
        }
    }
}
