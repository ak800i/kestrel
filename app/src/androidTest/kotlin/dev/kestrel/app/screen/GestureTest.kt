package dev.kestrel.app.screen

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.screen.act.ActionMethod
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.model.ScreenElement
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
        assertEquals(ActionResult.Done(ActionMethod.MAGNIFICATION), actions.resetZoom())
        assertEquals(1f, actions.zoomScale)
    }
}
