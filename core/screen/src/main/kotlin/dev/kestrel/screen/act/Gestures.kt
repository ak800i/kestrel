package dev.kestrel.screen.act

import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.graphics.Path

/** Builds Kestrel's gestures. Coordinates are screen pixels (design spec, section 9.3). */
internal object Gestures {
    private const val TAP_MILLIS = 50L
    private const val LONG_PRESS_MILLIS = 800L
    private const val SWIPE_MILLIS = 500L

    fun tap(x: Int, y: Int): GestureDescription = stroke(point(x, y), TAP_MILLIS)

    fun longPress(x: Int, y: Int): GestureDescription = stroke(point(x, y), LONG_PRESS_MILLIS)

    fun swipe(from: ScreenPoint, to: ScreenPoint, durationMillis: Long = SWIPE_MILLIS): GestureDescription =
        stroke(line(from, to), durationMillis)

    private fun point(x: Int, y: Int) = Path().apply { moveTo(x.toFloat(), y.toFloat()) }

    private fun line(from: ScreenPoint, to: ScreenPoint) = Path().apply {
        moveTo(from.x.toFloat(), from.y.toFloat())
        lineTo(to.x.toFloat(), to.y.toFloat())
    }

    private fun stroke(path: Path, durationMillis: Long): GestureDescription =
        GestureDescription.Builder().addStroke(StrokeDescription(path, 0, durationMillis)).build()
}
