package dev.kestrel.screen.overlay

import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager.LayoutParams
import dev.kestrel.screen.act.GestureRunner
import dev.kestrel.screen.act.OwnActions
import dev.kestrel.screen.act.Touch
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
 * runs (spike S3). The device is the clue that works on every version: Android 13 to 15, and Android 16 before QPR2
 * depending on feature flags, mark a finger's touches as accessibility events while a gesture service is on, and from
 * Android 16 QPR2, Android no longer cancels Kestrel's gesture when a finger touches the screen (design spec, section
 * 14.1 and B35). On Android 17 a click of Kestrel's through a node action arrives as a touch from the touchscreen, so
 * [OwnActions] classifies each touch by the device's rule (design D19 of sub-project 5).
 */
internal class TouchWatcher(private val host: OverlayHost, private val gestures: GestureRunner, private val own: OwnActions) {
    private val touches = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    private val classified = MutableSharedFlow<Touch>(extraBufferCapacity = 64)

    /** Whether the watcher's window is on the screen. */
    @Volatile
    var running = false
        private set

    // On purpose: the watcher only listens for touches and offers no click action.
    @SuppressLint("ClickableViewAccessibility")
    private val view = View(host.context).apply {
        setOnTouchListener { _, event ->
            val starts = event.actionMasked == MotionEvent.ACTION_OUTSIDE || event.actionMasked == MotionEvent.ACTION_DOWN
            if (starts) report(own.classify(event.deviceId, event.source, event.getToolType(0), event.flags, gestures.isRunning()))
            false
        }
    }

    init {
        own.report = ::report
    }

    /** Emits when the owner touches the screen while the watcher runs. */
    val ownerTouches: SharedFlow<Unit> = touches.asSharedFlow()

    /** Every touch that the watcher classified, Kestrel's own too, and the owner's touches that a signal found. */
    val all: SharedFlow<Touch> = classified.asSharedFlow()

    /**
     * Starts watching and returns whether the watcher's window is on the screen. A second call only updates the window's
     * layout. Each start forgets Kestrel's earlier actions, so that none of them can hide the owner's touch.
     */
    fun start(): Boolean {
        val shown = host.show(view, params())
        running = shown
        own.watching(shown)
        return shown
    }

    fun stop() {
        host.hide(view)
        running = false
        own.watching(false)
    }

    /** Reports [touch], and the owner's touches also to [ownerTouches]. Any thread may call it. */
    fun report(touch: Touch) {
        classified.tryEmit(touch)
        if (touch.owner) touches.tryEmit(Unit)
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
