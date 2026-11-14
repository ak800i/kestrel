package dev.kestrel.screen.act

import dev.kestrel.screen.model.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UncoveredTest {
    private val keyboard = IntRect(0, 1400, 1080, 2400)

    @Test
    fun theCentreWhenNothingCoversIt() {
        assertEquals(ScreenPoint(540, 1100), Uncovered.point(IntRect(0, 1000, 1080, 1200), listOf(IntRect(0, 0, 1080, 100))))
    }

    @Test
    fun theNearestUncoveredPointWhenTheKeyboardCoversTheCentre() {
        // The row's centre, (540, 1400), lies on the keyboard's top edge.
        assertEquals(ScreenPoint(607, 1387), Uncovered.point(IntRect(0, 1300, 1080, 1500), listOf(keyboard)))
    }

    @Test
    fun nothingWhenTheWindowsAboveCoverTheWholeElement() {
        assertNull(Uncovered.point(IntRect(0, 1500, 1080, 1700), listOf(keyboard)))
    }

    @Test
    fun aSwipeAreaLosesThePartUnderTheKeyboard() {
        assertEquals(IntRect(0, 200, 1080, 1400), Uncovered.area(IntRect(0, 200, 1080, 2000), listOf(keyboard), vertical = true))
    }

    @Test
    fun aWindowBesideTheSwipeLineDoesNotMatter() {
        val area = IntRect(0, 200, 1080, 2000)

        assertEquals(area, Uncovered.area(area, listOf(IntRect(0, 500, 300, 700)), vertical = true))
    }

    @Test
    fun aSidewaysSwipeAreaLosesThePartUnderAPanel() {
        assertEquals(IntRect(0, 500, 800, 900), Uncovered.area(IntRect(0, 500, 1080, 900), listOf(IntRect(800, 0, 1080, 2400)), vertical = false))
    }

    @Test
    fun aWindowInTheMiddleOfTheLineLeavesTheLargerPart() {
        assertEquals(IntRect(0, 900, 1080, 2000), Uncovered.area(IntRect(0, 200, 1080, 2000), listOf(IntRect(0, 600, 1080, 900)), vertical = true))
    }

    @Test
    fun theLongestFreeStretchWinsWhateverTheOrderOfTheWindows() {
        val area = IntRect(0, 0, 1080, 1000)
        val bar = IntRect(0, 300, 1080, 320)
        val popup = IntRect(0, 600, 1080, 980)

        assertEquals(IntRect(0, 0, 1080, 300), Uncovered.area(area, listOf(bar, popup), vertical = true))
        assertEquals(IntRect(0, 0, 1080, 300), Uncovered.area(area, listOf(popup, bar), vertical = true))
    }

    @Test
    fun nothingWhenTheFreeStretchIsShorterThanTheMinimum() {
        val area = IntRect(0, 1300, 1080, 2000)

        assertEquals(IntRect(0, 1300, 1080, 1400), Uncovered.area(area, listOf(keyboard), vertical = true, minLength = 100))
        assertNull(Uncovered.area(area, listOf(keyboard), vertical = true, minLength = 101))
    }

    @Test
    fun aWindowThatOnlyTouchesTheAreaHidesNothing() {
        val area = IntRect(0, 200, 1080, 1400)

        assertEquals(area, Uncovered.area(area, listOf(keyboard), vertical = true))
    }
}
