package dev.kestrel.screen.overlay

import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.KeyCharacterMap
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager.LayoutParams
import dev.kestrel.screen.act.GestureRunner
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * An invisible window of one pixel that reports the owner's touches anywhere on the screen, so that a touch can
 * stop a running task (design spec, sections 9.4 and 14.1). Android tells the window about every touch that starts
 * outside it, unless the touch lands on a window above it, such as an overlay that Kestrel shows later. Use it on
 * the main thread.
 *
 * Android injects the touches of Kestrel's gestures from its virtual input device, while a finger's touches come
 * from the touchscreen, so the watcher leaves out touches from the virtual device while one of Kestrel's gestures
 * runs (spike S3). The device is the clue that works on every version: Android 13 to 16 mark a finger's touches as
 * accessibility events while a gesture service is on, and Android 17 no longer cancels Kestrel's gesture when a
 * finger touches the screen.
 */
internal class TouchWatcher(private val host: OverlayHost, private val gestures: GestureRunner) {
    private val touches = MutableSharedFlow<Unit>(extraBufferCapacity = 16)

    // On purpose: the watcher only listens for touches and offers no click action.
    @SuppressLint("ClickableViewAccessibility")
    private val view = View(host.context).apply {
        setOnTouchListener { _, event ->
            val starts = event.actionMasked == MotionEvent.ACTION_OUTSIDE || event.actionMasked == MotionEvent.ACTION_DOWN
            val kestrels = gestures.isRunning() && event.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD
            if (starts && !kestrels) touches.tryEmit(Unit)
            false
        }
    }

    /** Emits when the owner touches the screen while the watcher runs. */
    val ownerTouches: SharedFlow<Unit> = touches.asSharedFlow()

    var isWatching: Boolean = false
        private set

    fun start() {
        if (isWatching) return
        host.show(view, params())
        isWatching = true
    }

    fun stop() {
        host.hide(view)
        isWatching = false
    }

    private fun params() = LayoutParams(
        1,
        1,
        LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCH_MODAL or LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            LayoutParams.FLAG_LAYOUT_IN_SCREEN or LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        setTitle("Kestrel touch watcher")
    }
}
