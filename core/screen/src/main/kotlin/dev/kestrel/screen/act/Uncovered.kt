package dev.kestrel.screen.act

import dev.kestrel.screen.model.IntRect

/**
 * Finds the parts of an element or a list that the windows above it leave uncovered, such as the keyboard, a popup or
 * the status bar, so that a fallback gesture reaches the element instead of the window above it (design spec,
 * section 9.3).
 */
internal object Uncovered {
    /** The candidate points lie on a grid with this many rows and columns. */
    private const val GRID = 8

    /**
     * The centre of [bounds] when no rectangle of [covers] contains it. Otherwise the uncovered point of a grid over
     * [bounds] that lies nearest to the centre, or null when [covers] hide every point of the grid.
     */
    fun point(bounds: IntRect, covers: List<IntRect>): ScreenPoint? {
        if (bounds.isEmpty) return null
        val centre = ScreenPoint(bounds.centerX, bounds.centerY)
        val grid = (0 until GRID).flatMap { row ->
            (0 until GRID).map { column ->
                ScreenPoint(
                    bounds.left + (2 * column + 1) * bounds.width / (2 * GRID),
                    bounds.top + (2 * row + 1) * bounds.height / (2 * GRID),
                )
            }
        }
        return (listOf(centre) + grid.sortedBy { distanceSquared(it, centre) })
            .firstOrNull { point -> covers.none { it.contains(point) } }
    }

    /**
     * [area] without the parts that [covers] hide on the line along which a swipe crosses it: the middle line from top
     * to bottom when [vertical], otherwise the one from side to side. Each cover on that line cuts the area at its
     * edge, and the larger part is kept. Null when nothing is left.
     */
    fun area(area: IntRect, covers: List<IntRect>, vertical: Boolean): IntRect? {
        var start = if (vertical) area.top else area.left
        var end = if (vertical) area.bottom else area.right
        for (cover in covers) {
            val onTheLine = if (vertical) area.centerX in cover.left until cover.right else area.centerY in cover.top until cover.bottom
            if (!onTheLine) continue
            val coverStart = maxOf(if (vertical) cover.top else cover.left, start)
            val coverEnd = minOf(if (vertical) cover.bottom else cover.right, end)
            if (coverStart >= coverEnd) continue
            if (coverStart - start >= end - coverEnd) end = coverStart else start = coverEnd
        }
        val clear = if (vertical) IntRect(area.left, start, area.right, end) else IntRect(start, area.top, end, area.bottom)
        return clear.takeUnless { it.isEmpty }
    }

    private fun IntRect.contains(point: ScreenPoint) = point.x in left until right && point.y in top until bottom

    private fun distanceSquared(a: ScreenPoint, b: ScreenPoint): Long {
        val dx = (a.x - b.x).toLong()
        val dy = (a.y - b.y).toLong()
        return dx * dx + dy * dy
    }
}
