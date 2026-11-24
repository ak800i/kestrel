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
}
