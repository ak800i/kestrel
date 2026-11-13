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
