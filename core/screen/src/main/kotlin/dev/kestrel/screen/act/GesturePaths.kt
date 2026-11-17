package dev.kestrel.screen.act

import dev.kestrel.screen.model.IntRect

/** One of the screen's four edges. */
enum class ScreenEdge { TOP, BOTTOM, LEFT, RIGHT }

/** A swipe from ([fromX], [fromY]) to ([toX], [toY]), in screen pixels. */
data class SwipeLine(val fromX: Int, val fromY: Int, val toX: Int, val toY: Int)

/** A pinch around ([centerX], [centerY]) whose two fingers, side by side, move from [startSpan] to [endSpan] pixels apart. */
data class PinchLine(val centerX: Int, val centerY: Int, val startSpan: Int, val endSpan: Int)

/**
 * The gestures that the owner names by voice, kept on the screen (design spec, section 9.3): Android refuses a gesture
 * with a point outside the screen, and a finger that would leave the screen ends the gesture early. "One pixel inside"
 * leaves the outermost row or column of pixels free.
 */
object GesturePaths {
    /** How much of the screen's width a pinch's larger span may cover. */
    private const val PINCH_WIDTH_SHARE = 0.8

    /** The smaller span of a pinch is this part of the larger one. */
    private const val SMALL_SPAN_DIVISOR = 4

    /** A swipe moves a third of the screen's height (up and down) or width (left and right). */
    private const val SWIPE_DIVISOR = 3

    /**
     * A pinch out ([out]) or in around ([centerX], [centerY]). The larger span is at most 80% of the screen's width and
     * at most twice the distance from the centre to the nearer side edge, so that both fingers stay on the screen; the
     * smaller span is a quarter of the larger. Null when the centre lies outside the screen, or so close to a side edge
     * that the fingers could not move.
     */
    fun limit(screen: IntRect, centerX: Int, centerY: Int, out: Boolean): PinchLine? {
        if (!screen.holds(centerX, centerY)) return null
        val nearer = minOf(centerX - screen.left, screen.right - 1 - centerX)
        val larger = minOf((screen.width * PINCH_WIDTH_SHARE).toInt(), 2 * nearer)
        val smaller = larger / SMALL_SPAN_DIVISOR
        if (smaller < 1) return null
        return if (out) PinchLine(centerX, centerY, smaller, larger) else PinchLine(centerX, centerY, larger, smaller)
    }

    /**
     * A swipe from ([fromX], [fromY]) in [fingerDirection], the direction in which the finger moves (the opposite of
     * `ScreenActions.scroll`'s direction, which is the content's), over a third of the screen's height or width, cut so
     * that it ends at least one pixel inside the screen. Null when the start lies outside the screen or the cut swipe
     * would not move.
     */
    fun limit(screen: IntRect, fromX: Int, fromY: Int, fingerDirection: ScrollDirection): SwipeLine? {
        if (!screen.holds(fromX, fromY)) return null
        val tall = screen.height / SWIPE_DIVISOR
        val wide = screen.width / SWIPE_DIVISOR
        val line = when (fingerDirection) {
            ScrollDirection.UP -> SwipeLine(fromX, fromY, fromX, maxOf(fromY - tall, screen.top + 1))
            ScrollDirection.DOWN -> SwipeLine(fromX, fromY, fromX, minOf(fromY + tall, screen.bottom - 2))
            ScrollDirection.LEFT -> SwipeLine(fromX, fromY, maxOf(fromX - wide, screen.left + 1), fromY)
            ScrollDirection.RIGHT -> SwipeLine(fromX, fromY, minOf(fromX + wide, screen.right - 2), fromY)
        }
        val moves = when (fingerDirection) {
            ScrollDirection.UP -> line.toY < fromY
            ScrollDirection.DOWN -> line.toY > fromY
            ScrollDirection.LEFT -> line.toX < fromX
            ScrollDirection.RIGHT -> line.toX > fromX
        }
        return line.takeIf { moves }
    }

    /**
     * Where a gesture on an element with [bounds] starts or ends: the point that a fallback tap would use (design spec,
     * section 9.3), that is the centre, or when windows above the element, given as [covers], hide the centre, the point
     * of a grid of 8 by 8 points over it that lies nearest to the centre and that they leave uncovered. Null when they
     * hide every point of the grid, so that the gesture would land on a window above, such as the keyboard.
     */
    fun pointOn(bounds: IntRect, covers: List<IntRect>): ScreenPoint? = Uncovered.point(bounds, covers)

    /** A swipe from one pixel inside [edge], at the middle of that edge, a third of the screen inwards. */
    fun fromEdge(screen: IntRect, edge: ScreenEdge): SwipeLine {
        val tall = screen.height / SWIPE_DIVISOR
        val wide = screen.width / SWIPE_DIVISOR
        return when (edge) {
            ScreenEdge.TOP -> SwipeLine(screen.centerX, screen.top + 1, screen.centerX, screen.top + 1 + tall)
            ScreenEdge.BOTTOM -> SwipeLine(screen.centerX, screen.bottom - 2, screen.centerX, screen.bottom - 2 - tall)
            ScreenEdge.LEFT -> SwipeLine(screen.left + 1, screen.centerY, screen.left + 1 + wide, screen.centerY)
            ScreenEdge.RIGHT -> SwipeLine(screen.right - 2, screen.centerY, screen.right - 2 - wide, screen.centerY)
        }
    }

    private fun IntRect.holds(x: Int, y: Int): Boolean = x in left until right && y in top until bottom
}
