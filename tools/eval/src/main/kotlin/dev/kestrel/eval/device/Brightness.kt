package dev.kestrel.eval.device

import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The brightness slider's percent from `screen_brightness` (design D8 of sub-project 6), as AOSP's Settings and SystemUI
 * show it: the setting's 1 to 255 as a float from 0 to 1 (`BrightnessSynchronizer.brightnessIntToFloat`), within the
 * device's minimum and maximum from `dumpsys display`, through `BrightnessUtils.convertLinearToGammaFloat`. One UI's
 * slider may use another curve; plan 6B's "Left open" says so.
 */
object Brightness {
    private val minimum = Regex("\\b(?:mBrightnessMin|mScreenBrightnessRangeMinimum)=(-?[0-9.]+)")
    private val maximum = Regex("\\b(?:mBrightnessMax|mScreenBrightnessRangeMaximum)=(-?[0-9.]+)")
    private const val R = 0.5
    private const val A = 0.17883277
    private const val B = 0.28466892
    private const val C = 0.55991073

    /** The device's lowest and highest brightness as floats from 0 to 1, or 0 to 1 when the dump gives no such pair. */
    fun range(dump: List<String>): ClosedFloatingPointRange<Double> {
        val low = dump.firstNotNullOfOrNull { minimum.find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
        val high = dump.firstNotNullOfOrNull { maximum.find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
        return if (low != null && high != null && low >= 0.0 && low < high && high <= 1.0) low..high else 0.0..1.0
    }

    /** The slider's percent for the setting [setting] (0 to 255) on AOSP's gamma curve. */
    fun percent(setting: Int, range: ClosedFloatingPointRange<Double>): Int {
        val linear = if (setting <= 1) 0.0 else (setting - 1) / 254.0
        val scaled = ((linear - range.start) / (range.endInclusive - range.start)).coerceIn(0.0, 1.0) * 12
        val gamma = if (scaled <= 1) sqrt(scaled) * R else A * ln(scaled - B) + C
        return (gamma * 100).roundToInt()
    }
}
