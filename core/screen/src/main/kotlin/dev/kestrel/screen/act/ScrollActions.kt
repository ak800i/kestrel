package dev.kestrel.screen.act

import android.view.accessibility.AccessibilityNodeInfo
import dev.kestrel.screen.model.ScrollAxis as ElementAxis

/** Which way to scroll. "Down" shows what is further down, as in Voice Access. */
enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

internal val ScrollDirection.isVertical: Boolean get() = this == ScrollDirection.UP || this == ScrollDirection.DOWN

/** The axis along which a list moves, as far as its accessibility node tells. */
internal enum class ScrollAxis { VERTICAL, HORIZONTAL, BOTH, UNKNOWN }

/**
 * Which accessibility actions scroll a list in a direction. Android's forward and backward actions do not say which
 * way they move, so Kestrel uses them only when it knows the list's axis and the axis matches: on a horizontal pager,
 * forward shows the next tab instead of more of the list (design spec, section 9.3).
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
    private val horizontalClasses = setOf(
        "android.widget.HorizontalScrollView",
        "androidx.viewpager.widget.ViewPager",
        "android.support.v4.view.ViewPager",
    )

    // Flutter gives its vertical scrollers this class, with only the forward and backward actions.
    private val verticalClasses = setOf("android.widget.ScrollView")

    /**
     * The axis of a list: from the directional actions that it offers, then from its class, and then from the shape of
     * its collection: a single row or a single column. Flutter reports 0 instead of 1 for the count that such a list
     * does not use, and Compose reports -1 for the number of items when it does not know it.
     */
    fun axis(offered: Set<Int>, className: String?, rows: Int?, columns: Int?): ScrollAxis {
        val vertical = offered.any { it in verticalActions }
        val horizontal = offered.any { it in horizontalActions }
        return when {
            vertical && horizontal -> ScrollAxis.BOTH
            vertical -> ScrollAxis.VERTICAL
            horizontal -> ScrollAxis.HORIZONTAL
            className in horizontalClasses -> ScrollAxis.HORIZONTAL
            className in verticalClasses -> ScrollAxis.VERTICAL
            rows.isOne() && columns.isMany() -> ScrollAxis.HORIZONTAL
            columns.isOne() && rows.isMany() -> ScrollAxis.VERTICAL
            else -> ScrollAxis.UNKNOWN
        }
    }

    private fun Int?.isOne() = this == 0 || this == 1

    private fun Int?.isMany() = this != null && this != 0 && this != 1

    /**
     * The actions that scroll a list with [axis] in [direction], in the order to try them. Forward and backward are
     * used only when the list's axis is known and matches: Flutter's pagers hide their axis, and on them forward would
     * turn the page. A list that scrolls both ways offers directional actions, so it never needs forward or backward.
     */
    fun forDirection(direction: ScrollDirection, axis: ScrollAxis): List<Int> {
        val alongTheAxis = when (axis) {
            ScrollAxis.VERTICAL -> direction.isVertical
            ScrollAxis.HORIZONTAL -> !direction.isVertical
            ScrollAxis.BOTH, ScrollAxis.UNKNOWN -> false
        }
        return when (direction) {
            ScrollDirection.DOWN -> listOf(DOWN, PAGE_DOWN) + if (alongTheAxis) listOf(FORWARD) else emptyList()
            ScrollDirection.UP -> listOf(UP, PAGE_UP) + if (alongTheAxis) listOf(BACKWARD) else emptyList()
            ScrollDirection.RIGHT -> listOf(RIGHT, PAGE_RIGHT) + if (alongTheAxis) listOf(FORWARD) else emptyList()
            ScrollDirection.LEFT -> listOf(LEFT, PAGE_LEFT) + if (alongTheAxis) listOf(BACKWARD) else emptyList()
        }
    }

    /**
     * Whether a swipe in [direction] may cross a list with [axis]: a list that moves along that axis, or both ways. A
     * list whose axis is unknown counts as vertical, because a sideways swipe across a row can archive a message or
     * start a call.
     */
    fun movesAlong(axis: ScrollAxis, direction: ScrollDirection): Boolean = when (axis) {
        ScrollAxis.BOTH -> true
        ScrollAxis.HORIZONTAL -> !direction.isVertical
        ScrollAxis.VERTICAL, ScrollAxis.UNKNOWN -> direction.isVertical
    }

    /** The axis that a snapshot's element shows (design D1 and D8 of sub-project 5): none for both ways or an unknown one. */
    fun elementAxis(axis: ScrollAxis): ElementAxis? = when (axis) {
        ScrollAxis.VERTICAL -> ElementAxis.VERTICAL
        ScrollAxis.HORIZONTAL -> ElementAxis.HORIZONTAL
        ScrollAxis.BOTH, ScrollAxis.UNKNOWN -> null
    }
}

/**
 * Firefox's web pages (design D11 of sub-project 5; research r57, Appendix B62). Gecko lists no scroll action on the
 * nodes inside a page; its page root, of the class [WEB_ROOT], scrolls the whole page by 0.8 of its height with the
 * forward and backward actions, and a node's long click does nothing although it answers true.
 */
internal object FirefoxPages {
    val PACKAGES = setOf("org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix")

    /** The class of Gecko's page root, compared as a string: the audit flags the class itself (design D1). */
    const val WEB_ROOT = "android.webkit.WebView"

    /**
     * The page root's action that scrolls a page's node in [direction], or null when only a swipe can: sideways, and
     * inside a scrollable region of the page ([inRegion]), whose scroll the root's action would not do.
     */
    fun rootAction(direction: ScrollDirection, inRegion: Boolean): Int? = when {
        inRegion -> null
        direction == ScrollDirection.DOWN -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        direction == ScrollDirection.UP -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        else -> null
    }
}
