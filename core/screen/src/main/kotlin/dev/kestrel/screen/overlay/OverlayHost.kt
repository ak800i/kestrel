package dev.kestrel.screen.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams

/**
 * Shows Kestrel's overlay windows. They are accessibility overlays, so Kestrel needs no "display over other apps"
 * permission, and snapshots leave them out (design spec, sections 9.2 and 9.4). Use it on the main thread.
 */
class OverlayHost(private val service: AccessibilityService) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val shown = mutableSetOf<View>()

    val context: Context get() = service

    fun show(view: View, params: LayoutParams) {
        if (shown.add(view)) windowManager.addView(view, params) else windowManager.updateViewLayout(view, params)
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

    fun hideAll() {
        shown.toList().forEach(::hide)
    }

    companion object {
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
