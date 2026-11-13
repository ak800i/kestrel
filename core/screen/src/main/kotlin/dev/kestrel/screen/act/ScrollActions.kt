package dev.kestrel.screen.act

import android.view.accessibility.AccessibilityNodeInfo

/** Which way to scroll. "Down" shows what is further down, as in Voice Access. */
enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

/** The axis along which a list moves, as far as its accessibility node tells. */
internal enum class ScrollAxis { VERTICAL, HORIZONTAL, UNKNOWN }

/**
 * Which accessibility actions scroll a list in a direction. Android's forward and backward actions do not say which
 * way they move, so Kestrel uses them only along the list's own axis: on a horizontal pager, forward shows the next
 * tab instead of more of the list (design spec, section 9.3).
 */
internal object ScrollActions {
    private const val UP = android.R.id.accessibilityActionScrollUp
    private const val DOWN = android.R.id.accessibilityActionScrollDown
    private const val LEFT = android.R.id.accessibilityActionScrollLeft
    private const val RIGHT = android.R.id.accessibilityActionScrollRight
    private const val PAGE_UP = android.R.id.accessibilityActionPageUp
    private const val PAGE_DOWN = android.R.id.accessibilityActionPageDown
    private const val PAGE_LEFT = android.R.id.accessibilityActionPageLeft
    private const val PAGE_RIGHT = android.R.id.accessibilityActionPageRight
    private const val FORWARD = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
    private const val BACKWARD = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD

    private val verticalActions = setOf(UP, DOWN, PAGE_UP, PAGE_DOWN)
    private val horizontalActions = setOf(LEFT, RIGHT, PAGE_LEFT, PAGE_RIGHT)
    private val horizontalClasses = setOf("android.widget.HorizontalScrollView", "androidx.viewpager.widget.ViewPager")

    /**
     * The axis of a list: from the directional actions that it offers, then from its class, and then from the shape of
     * its collection, such as a list with a single row.
     */
    fun axis(offered: Set<Int>, className: String?, rows: Int?, columns: Int?): ScrollAxis {
        val vertical = offered.any { it in verticalActions }
        val horizontal = offered.any { it in horizontalActions }
        return when {
            vertical && !horizontal -> ScrollAxis.VERTICAL
            horizontal && !vertical -> ScrollAxis.HORIZONTAL
            vertical -> ScrollAxis.UNKNOWN
            className in horizontalClasses -> ScrollAxis.HORIZONTAL
            rows == 1 && columns != 1 -> ScrollAxis.HORIZONTAL
            columns == 1 && rows != 1 -> ScrollAxis.VERTICAL
            else -> ScrollAxis.UNKNOWN
        }
    }

    /**
     * The actions that scroll a list with [axis] in [direction], in the order to try them. When the axis is unknown,
     * forward and backward count as vertical, because most lists scroll vertically.
     */
    fun forDirection(direction: ScrollDirection, axis: ScrollAxis): List<Int> {
        val horizontal = axis == ScrollAxis.HORIZONTAL
        return when (direction) {
            ScrollDirection.DOWN -> listOf(DOWN, PAGE_DOWN) + if (horizontal) emptyList() else listOf(FORWARD)
            ScrollDirection.UP -> listOf(UP, PAGE_UP) + if (horizontal) emptyList() else listOf(BACKWARD)
            ScrollDirection.RIGHT -> listOf(RIGHT, PAGE_RIGHT) + if (horizontal) listOf(FORWARD) else emptyList()
            ScrollDirection.LEFT -> listOf(LEFT, PAGE_LEFT) + if (horizontal) listOf(BACKWARD) else emptyList()
        }
    }
}
