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
     * to bottom when [vertical], otherwise the one from side to side. The longest stretch of that line that no cover
     * hides is kept, whatever the order of [covers]. Null when that stretch is shorter than [minLength].
     */
    fun area(area: IntRect, covers: List<IntRect>, vertical: Boolean, minLength: Int = 1): IntRect? {
        val lineStart = if (vertical) area.top else area.left
        val lineEnd = if (vertical) area.bottom else area.right
        val hidden = covers
            .filter { if (vertical) area.centerX in it.left until it.right else area.centerY in it.top until it.bottom }
            .map { maxOf(if (vertical) it.top else it.left, lineStart) to minOf(if (vertical) it.bottom else it.right, lineEnd) }
            .filter { (start, end) -> start < end }
            .sortedBy { it.first }
        var bestStart = lineStart
        var bestEnd = lineStart
        var free = lineStart
        for ((start, end) in hidden) {
            if (start - free > bestEnd - bestStart) {
                bestStart = free
                bestEnd = start
            }
            free = maxOf(free, end)
        }
        if (lineEnd - free > bestEnd - bestStart) {
            bestStart = free
            bestEnd = lineEnd
        }
        if (bestEnd - bestStart < maxOf(1, minLength)) return null
        return if (vertical) IntRect(area.left, bestStart, area.right, bestEnd) else IntRect(bestStart, area.top, bestEnd, area.bottom)
    }

    private fun IntRect.contains(point: ScreenPoint) = point.x in left until right && point.y in top until bottom

    private fun distanceSquared(a: ScreenPoint, b: ScreenPoint): Long {
        val dx = (a.x - b.x).toLong()
        val dy = (a.y - b.y).toLong()
        return dx * dx + dy * dy
    }
}
