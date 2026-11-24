package dev.kestrel.screen.act

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A slider's percent and the value of a percent (design D19 of sub-project 6). */
class SliderMathTest {
    @Test
    fun aPercentIsAWholeShareOfTheRangeAndAValueIsRoundedOnlyForWholeNumbers() {
        assertEquals(30, SliderMath.percent(0f, 100f, 30f))
        assertEquals(50, SliderMath.percent(0f, 1f, 0.5f))
        assertEquals(80, SliderMath.percent(0f, 65535f, 52428f))
        assertEquals(100, SliderMath.percent(0f, 10f, 12f))
        assertNull(SliderMath.percent(5f, 5f, 5f))
        assertEquals(30f, SliderMath.value(0f, 100f, 30, integer = true), 0f)
        assertEquals(19661f, SliderMath.value(0f, 65535f, 30, integer = true), 0f)
        assertEquals(0.3f, SliderMath.value(0f, 1f, 30, integer = false), 0.0001f)
        assertEquals(1f, SliderMath.value(0f, 1f, 140, integer = false), 0f)
        assertEquals(2, SliderMath.TOLERANCE)
    }

    @Test
    fun aSliderIsSetWithin2PointsOrAtTheWholeNumberNearestThePercentAndOneThatLeftIsNoLongerOnTheScreen() {
        val done = ActionResult.Done(ActionMethod.ACTION)
        // Settings' Font size has 7 positions, 0 to 6: 2 (33 %) is the nearest to 30 % and to 35 %, but not to 50 %.
        val fontSize = SliderMath.Range(0f, 6f, 2f, integer = true)
        assertEquals(done, SliderMath.readBack(gone = false, after = fontSize, percent = 30, moved = true))
        assertEquals(done, SliderMath.readBack(gone = false, after = fontSize, percent = 35, moved = true))
        assertEquals(ActionResult.Failed("The slider stopped at 33 %."), SliderMath.readBack(gone = false, after = fontSize, percent = 50, moved = true))
        // A range of fractions keeps the 2 points.
        assertEquals(ActionResult.Failed("The slider stopped at 33 %."), SliderMath.readBack(gone = false, after = SliderMath.Range(0f, 1f, 0.33f, integer = false), percent = 30, moved = true))
        assertEquals(done, SliderMath.readBack(gone = false, after = SliderMath.Range(0f, 1f, 0.32f, integer = false), percent = 30, moved = true))
        // Set-progress answers false when the slider already holds the value.
        val atHalf = SliderMath.Range(0f, 100f, 50f, integer = true)
        assertEquals(done, SliderMath.readBack(gone = false, after = atHalf, percent = 50, moved = false))
        assertEquals(ActionResult.Failed("The slider didn't move."), SliderMath.readBack(gone = false, after = atHalf, percent = 30, moved = false))
        assertEquals(ActionResult.Failed("It's no longer on the screen."), SliderMath.readBack(gone = true, after = null, percent = 30, moved = true))
        assertEquals(ActionResult.Failed("It isn't a slider."), SliderMath.readBack(gone = false, after = null, percent = 30, moved = true))
        assertEquals(ActionResult.Failed("It isn't a slider."), SliderMath.readBack(gone = false, after = SliderMath.Range(5f, 5f, 5f, integer = true), percent = 30, moved = true))
    }
}
