package dev.kestrel.screen.act

import kotlin.math.abs
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

    /** A slider's range as Android reports it: its ends, its value, and whether it holds only whole numbers. */
    data class Range(val min: Float, val max: Float, val current: Float, val integer: Boolean)

    /**
     * set_slider's answer from the slider read back after its action: [ActionResult.GONE] when the slider has left the
     * screen ([gone]), "It isn't a slider." without a range ([after]) that has width, and [ActionResult.Done] when it counts
     * as set to [percent]: within [TOLERANCE] points of it, or, for a range of whole numbers, at the whole number nearest
     * to it ([value]), as close as such a slider can come (Settings' Font size has 7 positions, 16.7 points apart).
     * Otherwise "The slider didn't move." when the action was refused ([moved] false; a View SeekBar and a Compose Slider
     * refuse set-progress to the value that they already hold, which counts as set above), else "The slider stopped at N %.".
     */
    fun readBack(gone: Boolean, after: Range?, percent: Int, moved: Boolean): ActionResult {
        if (gone) return ActionResult.GONE
        val shown = after?.let { percent(it.min, it.max, it.current) } ?: return NOT_A_SLIDER
        val asked = percent.coerceIn(0, 100)
        val nearest = after.integer && after.current == value(after.min, after.max, asked, integer = true)
        return when {
            abs(shown - asked) <= TOLERANCE || nearest -> ActionResult.Done(ActionMethod.ACTION)
            !moved -> ActionResult.Failed("The slider didn't move.")
            else -> ActionResult.Failed("The slider stopped at $shown %.")
        }
    }

    /** What set_slider answers for an element without a range, which is no slider. */
    val NOT_A_SLIDER = ActionResult.Failed("It isn't a slider.")
}
