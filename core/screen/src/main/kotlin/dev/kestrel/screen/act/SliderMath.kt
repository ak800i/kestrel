package dev.kestrel.screen.act

import kotlin.math.roundToInt

/**
 * A slider's value as a percent of its range, and the value of a percent (design D19 of sub-project 6): the snapshot
 * shows the first, and set_slider sets the second. Pure, for the JVM tests.
 */
object SliderMath {
    /** How many points away from the percent asked for a slider may stop and still count as set. */
    const val TOLERANCE = 2

    /** [current] as a whole percent of [min] to [max], from 0 to 100; null for a range without width. */
    fun percent(min: Float, max: Float, current: Float): Int? {
        if (!(max > min)) return null
        return ((current - min) / (max - min) * 100f).roundToInt().coerceIn(0, 100)
    }

    /** The value at [percent] (0 to 100) of [min] to [max], rounded to a whole number for a range of whole numbers. */
    fun value(min: Float, max: Float, percent: Int, integer: Boolean): Float {
        val value = min + percent.coerceIn(0, 100) / 100f * (max - min)
        return if (integer) value.roundToInt().toFloat().coerceIn(min, max) else value
    }
}
