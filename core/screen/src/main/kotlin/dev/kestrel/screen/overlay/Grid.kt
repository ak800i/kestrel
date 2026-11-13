package dev.kestrel.screen.overlay

import dev.kestrel.screen.model.IntRect

/**
 * Numbered cells over an area of the screen, for tapping and gestures where no element fits (design spec,
 * section 9.4). The cells are roughly square, and "more squares" and "fewer squares" step through [COLUMN_STEPS].
 */
data class Grid(val area: IntRect, val columns: Int) {
    val rows: Int = maxOf(1, ((columns.toLong() * area.height + area.width / 2) / area.width).toInt())

    val cellCount: Int get() = rows * columns

    /** The bounds of cell [number], counted from 1, row by row. The cells cover the whole area. */
    fun cell(number: Int): IntRect {
        require(number in 1..cellCount) { "The grid has no cell $number" }
        val row = (number - 1) / columns
        val column = (number - 1) % columns
        return IntRect(x(column), y(row), x(column + 1), y(row + 1))
    }

    fun more(): Grid = copy(columns = COLUMN_STEPS.firstOrNull { it > columns } ?: columns)

    fun fewer(): Grid = copy(columns = COLUMN_STEPS.lastOrNull { it < columns } ?: columns)

    private fun x(column: Int) = area.left + area.width * column / columns

    private fun y(row: Int) = area.top + area.height * row / rows

    companion object {
        val COLUMN_STEPS = listOf(2, 3, 4, 6, 8, 12)
        private const val DEFAULT_COLUMNS = 4

        fun over(area: IntRect) = Grid(area, DEFAULT_COLUMNS)
    }
}
