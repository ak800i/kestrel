package dev.kestrel.app.screen

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.model.ScreenElement
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * set_slider's action on the fixture's View SeekBar (set-progress, whole numbers from 0 to 100) and its Compose slider
 * (0 to 1 in 20 steps), at 30 %, 0 % and 100 %, each read back from the snapshot and from the fixture's own report
 * (design D19 of sub-project 6).
 */
@RunWith(AndroidJUnit4::class)
class SetSliderTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private fun element(match: (ScreenElement) -> Boolean): ScreenElement = runBlocking { screen.awaitElement(WAIT_MILLIS, match) }

    @Test
    fun theSeekBarAndTheComposeSliderGoToThePercentAndReadItBack() = runBlocking<Unit> {
        screen.open("CommandsActivity", "Commands fixture")
        val seekBar = element { it.viewId == "$FIXTURES:id/seek_bar" }
        val speed = element { it.slider && it.packageName == FIXTURES && it.key != seekBar.key }
        assertEquals(50, seekBar.sliderPercent)
        // Set-progress answers false for the value that the SeekBar already holds, which is set all the same.
        assertEquals(ActionResult.Done(dev.kestrel.screen.act.ActionMethod.ACTION), screen.session.actions.setSlider(seekBar.key, 50))

        for ((percent, status) in listOf(30 to "Seek bar 30", 0 to "Seek bar 0", 100 to "Seek bar 100")) {
            assertEquals(ActionResult.Done(dev.kestrel.screen.act.ActionMethod.ACTION), screen.session.actions.setSlider(seekBar.key, percent))
            element { it.label == status }
            assertEquals(percent, element { it.key == seekBar.key && it.sliderPercent == percent }.sliderPercent)
        }
        for ((percent, status) in listOf(30 to "Slider 0.30", 0 to "Slider 0.00", 100 to "Slider 1.00")) {
            assertEquals(ActionResult.Done(dev.kestrel.screen.act.ActionMethod.ACTION), screen.session.actions.setSlider(speed.key, percent))
            element { it.label == status }
        }
    }

    @Test
    fun anElementWithoutARangeIsNoSliderAndAGoneKeyIsNotOnTheScreen() = runBlocking<Unit> {
        screen.open("CommandsActivity", "Commands fixture")
        val button = element { it.label == "Send" }

        assertEquals(ActionResult.Failed("It isn't a slider."), screen.session.actions.setSlider(button.key, 30))
        assertEquals(ActionResult.Failed("It isn't on the screen."), screen.session.actions.setSlider("gone", 30))
        assertTrue(screen.session.snapshots.current().snapshot.elements.none { it.label == "Clicked Send" })
    }

    private companion object {
        const val WAIT_MILLIS = 15_000L
    }
}
