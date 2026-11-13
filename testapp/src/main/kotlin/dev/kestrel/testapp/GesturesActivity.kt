package dev.kestrel.testapp

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.hypot

/** A pad that names the last gesture made on it, in its status line. */
class GesturesActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = fixturePage("Gestures fixture")
        val status = page.addStatus()
        page.addView(
            GesturePadView(this).apply {
                id = R.id.gesture_pad
                contentDescription = "Gesture pad"
                onGesture = { status.text = it }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
    }
}

/**
 * Recognises a tap, a long press, a swipe, a drag (a long press and then a move) and a two-finger pinch, and
 * reports names such as "Tap", "Swipe up", "Drag right" or "Pinch out".
 */
class GesturePadView(context: Context) : View(context) {
    var onGesture: (String) -> Unit = {}

    private var downAt = 0L
    private var downX = 0f
    private var downY = 0f
    private var movedAt = 0L
    private var pinching = false
    private var startSpan = 0f
    private var endSpan = 0f

    init {
        setBackgroundColor(0xFFE3F2FD.toInt())
    }

    // On purpose: the pad reads raw touches and offers no click action.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downAt = event.eventTime
                downX = event.x
                downY = event.y
                movedAt = 0L
                pinching = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount == 2) {
                pinching = true
                startSpan = span(event)
                endSpan = startSpan
            }
            MotionEvent.ACTION_MOVE -> when {
                pinching && event.pointerCount >= 2 -> endSpan = span(event)
                movedAt == 0L && hypot(event.x - downX, event.y - downY) > SLOP -> movedAt = event.eventTime
            }
            MotionEvent.ACTION_UP -> onGesture(name(event))
        }
        return true
    }

    private fun name(up: MotionEvent): String = when {
        pinching -> if (endSpan > startSpan) "Pinch out" else "Pinch in"
        movedAt == 0L -> if (up.eventTime - downAt >= LONG_PRESS_MILLIS) "Long press" else "Tap"
        movedAt - downAt >= LONG_PRESS_MILLIS -> "Drag ${direction(up)}"
        else -> "Swipe ${direction(up)}"
    }

    private fun direction(up: MotionEvent): String {
        val dx = up.x - downX
        val dy = up.y - downY
        return if (abs(dx) > abs(dy)) {
            if (dx > 0) "right" else "left"
        } else {
            if (dy > 0) "down" else "up"
        }
    }

    private fun span(event: MotionEvent) = hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))

    private companion object {
        const val SLOP = 30f
        const val LONG_PRESS_MILLIS = 500L
    }
}
