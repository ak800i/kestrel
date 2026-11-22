package dev.kestrel.screen.capture

import dev.kestrel.screen.model.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of the screenshot (design D20 of sub-project 5): the app's uncovered areas and the black check. */
class ScreenCaptureRulesTest {
    @Test
    fun theUncoveredAreasLeaveOutEveryCoverAndDoNotOverlap() {
        val area = IntRect(0, 0, 100, 200)
        val bar = IntRect(0, 0, 100, 20)
        val keyboard = IntRect(0, 150, 100, 200)
        val dialog = IntRect(30, 60, 70, 100)

        val parts = ScreenCapture.uncovered(area, listOf(bar, keyboard, dialog))

        val pixels = parts.sumOf { it.width * it.height }
        assertEquals(100 * 200 - 100 * 20 - 100 * 50 - 40 * 40, pixels)
        for (part in parts) {
            assertTrue(area.contains(part))
            for (cover in listOf(bar, keyboard, dialog)) assertEquals(null, part.intersect(cover))
            for (other in parts) if (other !== part) assertEquals(null, part.intersect(other))
        }
        assertEquals(listOf(area), ScreenCapture.uncovered(area, listOf(IntRect(200, 0, 300, 10))))
    }

    @Test
    fun aWindowCountsAsBlackOnlyWhenNinetyNinePercentOfItsOwnPixelsArePureBlack() {
        val width = 10
        val black = 0xFF000000.toInt()
        val pixels = IntArray(width * 10) { black }
        val all = listOf(IntRect(0, 0, 10, 10))
        assertTrue(ScreenCapture.mostlyBlack(pixels, width, all))

        // A dark theme's near-black is not black.
        pixels[0] = 0xFF121212.toInt()
        pixels[1] = 0xFF121212.toInt()
        assertFalse(ScreenCapture.mostlyBlack(pixels, width, all))
        // Only the app's own areas count: the two grey pixels lie in a covered row.
        assertTrue(ScreenCapture.mostlyBlack(pixels, width, listOf(IntRect(0, 1, 10, 10))))
        assertFalse(ScreenCapture.mostlyBlack(pixels, width, emptyList()))
    }
}
