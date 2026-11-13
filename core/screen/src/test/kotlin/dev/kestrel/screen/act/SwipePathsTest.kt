package dev.kestrel.screen.act

import dev.kestrel.screen.model.IntRect
import org.junit.Assert.assertEquals
import org.junit.Test

class SwipePathsTest {
    private val area = IntRect(100, 200, 1100, 2200)

    @Test
    fun scrollingDownMovesTheFingerUpWithinTheMiddleOfTheArea() {
        assertEquals(ScreenPoint(600, 1600) to ScreenPoint(600, 800), SwipePaths.forScroll(area, ScrollDirection.DOWN))
        assertEquals(ScreenPoint(600, 800) to ScreenPoint(600, 1600), SwipePaths.forScroll(area, ScrollDirection.UP))
    }

    @Test
    fun scrollingRightMovesTheFingerLeft() {
        assertEquals(ScreenPoint(800, 1200) to ScreenPoint(400, 1200), SwipePaths.forScroll(area, ScrollDirection.RIGHT))
        assertEquals(ScreenPoint(400, 1200) to ScreenPoint(800, 1200), SwipePaths.forScroll(area, ScrollDirection.LEFT))
    }
}
