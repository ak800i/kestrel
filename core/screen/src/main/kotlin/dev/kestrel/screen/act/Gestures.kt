package dev.kestrel.screen.act

import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.graphics.Path

/** Builds Kestrel's gestures. Coordinates are screen pixels (design spec, section 9.3). */
internal object Gestures {
    private const val TAP_MILLIS = 50L
    private const val LONG_PRESS_MILLIS = 800L
    private const val SWIPE_MILLIS = 500L
    private const val DRAG_MOVE_MILLIS = 600L
    private const val PINCH_MILLIS = 600L
    private const val DRAG_DRIFT_PIXELS = 10

    fun tap(x: Int, y: Int): GestureDescription = gesture(StrokeDescription(point(x, y), 0, TAP_MILLIS))

    fun longPress(x: Int, y: Int): GestureDescription = gesture(StrokeDescription(point(x, y), 0, LONG_PRESS_MILLIS))

    fun swipe(from: ScreenPoint, to: ScreenPoint, durationMillis: Long = SWIPE_MILLIS): GestureDescription =
        gesture(StrokeDescription(line(from, to), 0, durationMillis))

    /**
     * A drag is a long press that continues into a move, so it is dispatched in two parts. Android ends a
     * continuing stroke at its last change of position, so the press drifts slowly by a few pixels, less than a
     * touch slop, to last its whole time.
     */
    fun drag(from: ScreenPoint, to: ScreenPoint): Array<GestureDescription> {
        val pressed = ScreenPoint(from.x + DRAG_DRIFT_PIXELS, from.y)
        val hold = StrokeDescription(line(from, pressed), 0, LONG_PRESS_MILLIS, true)
        val move = hold.continueStroke(line(pressed, to), 0, DRAG_MOVE_MILLIS, false)
        return arrayOf(gesture(hold), gesture(move))
    }

    /** Two fingers that move apart ([endSpan] larger than [startSpan]) or together, side by side around [center]. */
    fun pinch(center: ScreenPoint, startSpan: Int, endSpan: Int): GestureDescription {
        fun finger(side: Int) = StrokeDescription(
            line(ScreenPoint(center.x + side * startSpan / 2, center.y), ScreenPoint(center.x + side * endSpan / 2, center.y)),
            0,
            PINCH_MILLIS,
        )
        return GestureDescription.Builder().addStroke(finger(-1)).addStroke(finger(1)).build()
    }

    private fun point(x: Int, y: Int) = Path().apply { moveTo(x.toFloat(), y.toFloat()) }

    private fun line(from: ScreenPoint, to: ScreenPoint) = Path().apply {
        moveTo(from.x.toFloat(), from.y.toFloat())
        lineTo(to.x.toFloat(), to.y.toFloat())
    }

    private fun gesture(stroke: StrokeDescription): GestureDescription = GestureDescription.Builder().addStroke(stroke).build()
}
