package dev.kestrel.app.screen

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.screen.act.ActionMethod
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.model.ScreenElement
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GestureTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val actions get() = screen.session.actions
    private val done = ActionResult.Done(ActionMethod.GESTURE)

    private fun openPad(): ScreenElement {
        screen.open("GesturesActivity", "Gestures fixture")
        return runBlocking { screen.awaitElement { it.label == "Gesture pad" } }
    }

    private suspend fun padReports(gesture: String) = screen.awaitElement { it.label == gesture }

    /** Where the current snapshot shows the pad, without waiting for the screen to change. */
    private suspend fun padNow() = screen.session.snapshots.current().snapshot.elements.single { it.label == "Gesture pad" }.bounds

    @Test
    fun tapAndLongPressAtAPoint() = runBlocking {
        val pad = openPad().bounds

        assertEquals(done, actions.tapAt(pad.centerX, pad.centerY))
        padReports("Tap")
        assertEquals(done, actions.longPressAt(pad.centerX, pad.centerY))
        padReports("Long press")
        Unit
    }

    @Test
    fun aLongPressLastsLongerThanAndroidsTouchAndHoldDelay() = runBlocking {
        val original = screen.shell("settings get secure long_press_timeout").trim()
        try {
            // The longest touch-and-hold delay that Android's accessibility settings offer.
            screen.shell("settings put secure long_press_timeout 1500")
            screen.open("ViewsActivity", "Views fixture")
            val send = screen.awaitElement { it.label == "SEND" && it.clickable }.bounds

            assertEquals(done, actions.longPressAt(send.centerX, send.centerY))

            screen.awaitElement { it.label == "Long-pressed Send" }
            Unit
        } finally {
            if (original == "null") {
                screen.shell("settings delete secure long_press_timeout")
            } else {
                screen.shell("settings put secure long_press_timeout $original")
            }
        }
    }

    @Test
    fun aPointOutsideTheScreenFailsInsteadOfThrowing() = runBlocking {
        openPad()

        assertEquals(ActionResult.Failed("The point is outside the screen"), actions.tapAt(-5, 100))
    }

    @Test
    fun swipeAndDragAreToldApart() = runBlocking {
        val pad = openPad().bounds

        assertEquals(done, actions.swipe(pad.centerX, pad.centerY + 300, pad.centerX, pad.centerY - 300))
        padReports("Swipe up")
        assertEquals(done, actions.drag(pad.centerX - 300, pad.centerY, pad.centerX + 300, pad.centerY))
        padReports("Drag right")
        Unit
    }

    @Test
    fun pinchOutAndIn() = runBlocking {
        val pad = openPad().bounds

        assertEquals(done, actions.pinch(pad.centerX, pad.centerY, startSpan = 200, endSpan = 700))
        padReports("Pinch out")
        assertEquals(done, actions.pinch(pad.centerX, pad.centerY, startSpan = 700, endSpan = 200))
        padReports("Pinch in")
        Unit
    }

    @Test
    fun zoomMagnifiesTheScreenAndResetZoomUndoesIt() = runBlocking {
        val pad = openPad().bounds

        assertEquals(ActionResult.Done(ActionMethod.MAGNIFICATION), actions.zoom(2f, pad.centerX, pad.centerY))
        assertEquals(2f, actions.zoomScale)
        // Magnification moves every element, so the next snapshot must show the pad at its magnified size. The width
        // is compared because the fixture's layout may still move the pad up or down a little after it opens.
        assertNotEquals(pad.width, padNow().width)
        assertEquals(ActionResult.Done(ActionMethod.MAGNIFICATION), actions.resetZoom())
        assertEquals(1f, actions.zoomScale)
        assertEquals(pad.width, padNow().width)
    }
}
