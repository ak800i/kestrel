package dev.kestrel.screen.overlay

import dev.kestrel.screen.model.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GridTest {
    private val screen = IntRect(0, 0, 1080, 2400)
    private val grid = Grid.over(screen)

    @Test
    fun cellsAreRoughlySquareAndNumberedRowByRow() {
        assertEquals(4, grid.columns)
        assertEquals(9, grid.rows)
        assertEquals(IntRect(0, 0, 270, 266), grid.cell(1))
        assertEquals(IntRect(270, 0, 540, 266), grid.cell(2))
        assertEquals(IntRect(0, 266, 270, 533), grid.cell(5))
    }

    @Test
    fun theCellsCoverTheWholeAreaWithoutGaps() {
        assertEquals(IntRect(810, 2133, 1080, 2400), grid.cell(grid.cellCount))
        for (number in 1 until grid.cellCount) {
            if (number % grid.columns != 0) assertEquals(grid.cell(number).right, grid.cell(number + 1).left)
        }
        assertThrows(IllegalArgumentException::class.java) { grid.cell(grid.cellCount + 1) }
    }

    @Test
    fun moreAndFewerSquaresStopAtTheEnds() {
        assertEquals(6, grid.more().columns)
        assertEquals(3, grid.fewer().columns)
        assertEquals(12, Grid(screen, 12).more().columns)
        assertEquals(2, Grid(screen, 2).fewer().columns)
    }
}
