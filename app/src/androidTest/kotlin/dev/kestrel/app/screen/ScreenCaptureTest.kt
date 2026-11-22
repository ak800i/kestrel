package dev.kestrel.app.screen

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.screen.capture.CaptureResult
import dev.kestrel.screen.capture.ScreenCapture
import dev.kestrel.screen.model.ScreenTree
import dev.kestrel.screen.model.WindowSummary
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Screenshots for the agent's look_closer (design D20 of sub-project 5; r49): the capture of one window from API 34, the
 * capture of the display with Kestrel's overlays hidden, which API 33 needs and which this test runs on every level, and
 * a secure window on both. The images stay in memory and are recycled.
 */
@RunWith(AndroidJUnit4::class)
class ScreenCaptureTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val session get() = screen.session

    private suspend fun front(): WindowSummary = ScreenTree.frontWindow(session.snapshots.current().snapshot)!!

    /** ViewsActivity's dialog "Delete draft?", a window that does not start at (0, 0). */
    private suspend fun dialog(): WindowSummary {
        screen.open("ViewsActivity", "Views fixture")
        session.actions.tap(screen.awaitElement { it.label == "SHOW DIALOG" }.key)
        val title = screen.awaitElement { it.label == "Delete draft?" }
        return session.snapshots.current().snapshot.windows.first { it.id == title.windowId }
    }

    @Test
    fun theWindowCaptureStartsAtItsWindowAndKeepsTheInterval() = runBlocking {
        assumeTrue("takeScreenshotOfWindow exists from API 34", Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        val window = dialog()
        assertTrue(window.bounds.left > 0 || window.bounds.top > 0)

        val started = SystemClock.uptimeMillis()
        val first = session.capture.capture(window.id, wholeDisplay = false) as CaptureResult.Image
        val second = session.capture.capture(window.id, wholeDisplay = false) as CaptureResult.Image
        val took = SystemClock.uptimeMillis() - started
        try {
            // The image covers the window; a dialog's shadow adds the same margin on each side.
            assertTrue(first.originX <= window.bounds.left && first.originY <= window.bounds.top)
            assertTrue(first.originX + first.bitmap.width >= window.bounds.right && first.originY + first.bitmap.height >= window.bounds.bottom)
            assertTrue(first.blocked.isEmpty())
            assertTrue("two captures took $took ms", took >= ScreenCapture.INTERVAL_MILLIS)
        } finally {
            first.bitmap.recycle()
            second.bitmap.recycle()
        }
    }

    @Test
    fun theDisplayCaptureHidesKestrelsOverlaysAndBlacksOutTheBars() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val window = front()
        val plain = session.capture.capture(window.id, wholeDisplay = true) as CaptureResult.Image
        session.showNumbers()
        withTimeout(5_000) { while (session.numberBadges.isEmpty()) delay(100) }
        val hidden = session.capture.capture(window.id, wholeDisplay = true) as CaptureResult.Image
        try {
            // The numbers' blue is in neither image, although the numbers show.
            assertEquals(0, blue(plain.bitmap))
            assertEquals(0, blue(hidden.bitmap))
            // Every part that a window above the app covers, such as the status bar, is black in the image, and the app's
            // own areas leave it out.
            for (part in hidden.blocked) {
                assertEquals(Color.BLACK, hidden.bitmap.getPixel(part.centerX - hidden.originX, part.centerY - hidden.originY))
            }
            assertTrue(hidden.appPixels.none { part -> hidden.blocked.any { part.intersect(it) != null } })
            // The numbers show again at once.
            val after = screen.automation.takeScreenshot()!!
            try {
                assertTrue(blue(after) > 0)
            } finally {
                after.recycle()
            }
        } finally {
            session.hideNumbers()
            plain.bitmap.recycle()
            hidden.bitmap.recycle()
        }
    }

    @Test
    fun aSecureWindowIsSecureOnBothPaths() = runBlocking {
        screen.open("SecureActivity", "Secure fixture")
        val window = front()

        assertEquals(CaptureResult.Secure, session.capture.capture(window.id, wholeDisplay = true))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            assertEquals(CaptureResult.Secure, session.capture.capture(window.id, wholeDisplay = false))
        }
    }

    // The pixels in the numbers' blue (NumbersOverlay's 0xE61565C0 over the fixture), counted in every fourth row and column.
    private fun blue(bitmap: Bitmap): Int {
        var count = 0
        for (y in 0 until bitmap.height step 4) {
            for (x in 0 until bitmap.width step 4) {
                val pixel = bitmap.getPixel(x, y)
                if (abs(Color.red(pixel) - 0x15) < 40 && abs(Color.green(pixel) - 0x65) < 40 && abs(Color.blue(pixel) - 0xC0) < 40) count++
            }
        }
        return count
    }

}
