package dev.kestrel.screen.act

import dev.kestrel.screen.model.IntRect
import kotlin.math.roundToInt

internal data class ScreenPoint(val x: Int, val y: Int)

/** Where a swipe that scrolls an area starts and ends. It stays clear of the screen edges, where swipes mean "back" or "home". */
internal object SwipePaths {
    private const val NEAR = 0.3
    private const val FAR = 0.7

    /**
     * The shortest area that [forScroll] may swipe across: its swipe then moves at least twice Android's touch slop, so
     * that Android takes it for a scroll and not for a tap or a long press on a row.
     */
    fun shortestScrollArea(touchSlop: Int): Int = (2 * touchSlop / (FAR - NEAR)).roundToInt()

    fun forScroll(area: IntRect, direction: ScrollDirection): Pair<ScreenPoint, ScreenPoint> {
        fun x(fraction: Double) = area.left + (area.width * fraction).toInt()
        fun y(fraction: Double) = area.top + (area.height * fraction).toInt()
        return when (direction) {
            ScrollDirection.DOWN -> ScreenPoint(area.centerX, y(FAR)) to ScreenPoint(area.centerX, y(NEAR))
            ScrollDirection.UP -> ScreenPoint(area.centerX, y(NEAR)) to ScreenPoint(area.centerX, y(FAR))
            ScrollDirection.RIGHT -> ScreenPoint(x(FAR), area.centerY) to ScreenPoint(x(NEAR), area.centerY)
            ScrollDirection.LEFT -> ScreenPoint(x(NEAR), area.centerY) to ScreenPoint(x(FAR), area.centerY)
        }
    }
}
