package dev.kestrel.app.screen

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.screen.act.ActionMethod
import dev.kestrel.screen.act.ActionResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * SystemUI's brightness slider, which Settings' "Brightness level" opens, at 30 %, 50 % and 80 % with set_slider's action,
 * and `screen_brightness` read back on AOSP's gamma curve, as :tools:eval's `BrightnessNear` reads it (design D8 and D19
 * of sub-project 6). It puts the brightness and its mode back.
 */
@RunWith(AndroidJUnit4::class)
class BrightnessSliderTest {
    @get:Rule
    val screen = ScreenServiceRule()

    @Test
    fun theBrightnessSliderFollowsAospsGammaCurve() = runBlocking<Unit> {
        assumeTrue("Only on kestrel-api37", screen.shell("getprop ro.boot.qemu.avd_name").trim() == "kestrel-api37")
        val mode = screen.shell("settings get system screen_brightness_mode").trim()
        val level = screen.shell("settings get system screen_brightness").trim()
        try {
            screen.shell("settings put system screen_brightness_mode 0")
            screen.shell("am start -W -a com.android.intent.action.SHOW_BRIGHTNESS_DIALOG")
            val slider = withTimeoutOrNull(5_000) { screen.awaitElement(5_000) { it.slider && it.packageName == SYSTEM_UI } } ?: run {
                screen.shell("am start -W -a android.settings.DISPLAY_SETTINGS")
                val entry = screen.awaitElement(10_000) { it.label == "Brightness level" }
                screen.session.actions.tap(entry.key)
                screen.awaitElement(10_000) { it.slider && it.packageName == SYSTEM_UI }
            }
            for (percent in listOf(30, 50, 80)) {
                assertEquals(ActionResult.Done(ActionMethod.ACTION), screen.session.actions.setSlider(slider.key, percent))
                delay(1_000)
                val setting = screen.shell("settings get system screen_brightness").trim().toInt()
                assertTrue("$percent % gave screen_brightness $setting", abs(gammaPercent(setting) - percent) <= 3)
            }
        } finally {
            screen.shell("input keyevent KEYCODE_BACK")
            screen.shell("am force-stop com.android.settings")
            screen.shell("settings put system screen_brightness $level")
            screen.shell("settings put system screen_brightness_mode $mode")
        }
    }

    // AOSP's BrightnessUtils.convertLinearToGammaFloat on the setting's scale of 1 to 255 (the float (setting - 1) / 254),
    // with Android 17's range of 0 to 1 on kestrel-api37, in percent: :tools:eval's Brightness.percent, kept here as the
    // oracle that this test confirms.
    private fun gammaPercent(setting: Int): Int {
        val scaled = ((setting - 1) / 254f).coerceIn(0f, 1f) * 12f
        val gamma = if (scaled <= 1f) sqrt(scaled) * 0.5f else 0.17883277f * ln(scaled - 0.28466892f) + 0.55991073f
        return (gamma * 100f).roundToInt()
    }

    private companion object {
        const val SYSTEM_UI = "com.android.systemui"
    }
}
