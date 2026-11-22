package dev.kestrel.app.screen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.agent.port.Capture
import dev.kestrel.app.agent.eyes.AppEyes
import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.screen.capture.CaptureResult
import dev.kestrel.screen.capture.ScreenCapture
import dev.kestrel.screen.model.ScreenTree
import dev.kestrel.screen.model.WindowSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.CoroutineContext
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
    fun aFeedbackBarThatShowsDuringTheDisplayCaptureIsNotInIt() = runBlocking {
        screen.open("GesturesActivity", "Gestures fixture")
        session.hideFeedback()
        val window = front()
        val plain = session.capture.capture(window.id, wholeDisplay = true) as CaptureResult.Image
        // The bar is on the screen when the next capture hides the overlays, with one line, so that the capture paints that
        // line black; Android needs a moment to draw a new window the first time.
        session.showFeedback("Heard")
        withTimeout(5_000) { while (!screen.showsWindow("Kestrel feedback bar")) delay(100) }
        delay(ScreenCapture.INTERVAL_MILLIS + 100)
        val shot = async(Dispatchers.Default) { session.capture.capture(window.id, wholeDisplay = true) }
        // From another coroutine, while the capture waits, the bar shows again and again with a text that grows by a line
        // every other time.
        val texts = launch {
            var words = 4
            while (isActive) {
                session.showFeedback("Heard" + " words".repeat(words))
                words = minOf(words + 4, 200)
                delay(5)
            }
        }
        val result = try {
            shot.await()
        } finally {
            texts.cancelAndJoin()
            session.hideFeedback()
        }
        val hidden = result as CaptureResult.Image
        try {
            assertEquals(plain.originX to plain.originY, hidden.originX to hidden.originY)
            // None of the bar's pixels, its white text or its dark background, is in the image: outside the parts that the
            // capture painted black, it equals the capture without the bar. The test names only a count, never a pixel.
            var changed = 0
            for (y in 0 until minOf(plain.bitmap.height, hidden.bitmap.height) step 2) {
                for (x in 0 until minOf(plain.bitmap.width, hidden.bitmap.width) step 2) {
                    val sx = x + hidden.originX
                    val sy = y + hidden.originY
                    if (hidden.blocked.any { sx >= it.left && sx < it.right && sy >= it.top && sy < it.bottom }) continue
                    val a = plain.bitmap.getPixel(x, y)
                    val b = hidden.bitmap.getPixel(x, y)
                    val difference = abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))
                    if (difference > 96) changed++
                }
            }
            assertEquals(0, changed)
        } finally {
            plain.bitmap.recycle()
            hidden.bitmap.recycle()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aCancellationAsTheHideEndsLeavesTheOverlaysShownLaterVisible() = runBlocking {
        screen.open("GesturesActivity", "Gestures fixture")
        session.hideFeedback()
        withTimeout(5_000) { while (screen.showsWindow("Kestrel feedback bar")) delay(100) }
        val before = screen.automation.takeScreenshot()!!
        // The app's Dispatchers.Main resumes the hide's last delay on the main thread without checking the job, so a
        // cancellation from another thread can come while the hide returns. In the device tests, Dispatchers.Main is
        // kotlinx-coroutines-test's wrapper, which brings the delay back through a dispatch that checks the job first; the
        // main looper's immediate dispatcher, as Main, resumes on the main thread without the check, as the app's does.
        Dispatchers.setMain(Handler(Looper.getMainLooper()).asCoroutineDispatcher().immediate)
        try {
            val cancel = CancelOnResume(HIDE_MILLIS)
            // The capture's coroutine holds the handle as displayShot does, and is cancelled as the hide's last wait ends.
            val capture = launch(Dispatchers.Main + cancel) {
                cancel.arm(coroutineContext.job)
                val hidden = session.hideAllForCapture() ?: return@launch
                try {
                    delay(5_000)
                } finally {
                    hidden.close()
                }
            }
            capture.join()
            assertTrue(cancel.fired && capture.isCancelled)
        } finally {
            Dispatchers.resetMain()
        }

        // The bar that Kestrel shows afterwards is drawn: its dark background changes the top of the screen.
        session.showFeedback("Heard")
        try {
            val changed = withTimeoutOrNull(3_000) {
                while (true) {
                    val after = screen.automation.takeScreenshot()!!
                    val count = try {
                        changedAtTheTop(before, after)
                    } finally {
                        after.recycle()
                    }
                    if (count > 500) return@withTimeoutOrNull count
                    delay(100)
                }
            }
            assertTrue("the feedback bar stayed transparent", changed != null)
        } finally {
            session.hideFeedback()
            before.recycle()
        }
    }

    /**
     * Cancels the job that [arm] names, on the main thread, as the first resumption of its coroutine that comes at least
     * [afterMillis] after [arm] begins and before the resumed code runs on.
     */
    private class CancelOnResume(private val afterMillis: Long) : ThreadContextElement<Unit> {
        companion object Key : CoroutineContext.Key<CancelOnResume>

        override val key: CoroutineContext.Key<*> get() = Key

        private var target: Job? = null
        private var armedAt = 0L

        var fired = false
            private set

        fun arm(job: Job) {
            target = job
            armedAt = SystemClock.uptimeMillis()
        }

        override fun updateThreadContext(context: CoroutineContext) {
            val job = target ?: return
            if (fired || SystemClock.uptimeMillis() - armedAt < afterMillis) return
            fired = true
            job.cancel()
        }

        override fun restoreThreadContext(context: CoroutineContext, oldState: Unit) = Unit
    }

    // The pixels in the top eighth of the screen that differ clearly between the two screenshots, in every other row and column.
    private fun changedAtTheTop(a: Bitmap, b: Bitmap): Int {
        var changed = 0
        for (y in 0 until minOf(a.height, b.height) / 8 step 2) {
            for (x in 0 until minOf(a.width, b.width) step 2) {
                val p = a.getPixel(x, y)
                val q = b.getPixel(x, y)
                val difference = abs(Color.red(p) - Color.red(q)) + abs(Color.green(p) - Color.green(q)) + abs(Color.blue(p) - Color.blue(q))
                if (difference > 96) changed++
            }
        }
        return changed
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

    @Test
    fun theEyesMaskAnElementWhereItIsInADialogThatDoesNotStartAtTheCorner() = runBlocking {
        val window = dialog()
        val keep = screen.awaitElement { it.label.equals("Keep", ignoreCase = true) && it.windowId == window.id }
        val title = screen.awaitElement { it.label == "Delete draft?" && it.windowId == window.id }
        val eyes = AppEyes({ session }, { VoiceTests.graph.models }, { emptySet() }) { _, _ -> }
        val capture = eyes.capture() as Capture.Captured
        capture.image.use {
            assertTrue(capture.originX > 0 || capture.originY > 0)
            val jpeg = eyes.encode(capture, listOf(keep.bounds), emptyList())
            val image = BitmapFactory.decodeByteArray(jpeg.bytes, 0, jpeg.bytes.size)
            try {
                val scale = image.width.toDouble() / capture.image.width
                fun luminance(x: Int, y: Int): Int {
                    val pixel = image.getPixel(((x - capture.originX) * scale).toInt(), ((y - capture.originY) * scale).toInt())
                    return (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
                }
                // Inside the masked button everything is black, JPEG's few levels aside; the dialog's light background
                // beside it is not.
                val b = keep.bounds
                for (x in listOf(b.left + b.width / 4, b.centerX, b.right - b.width / 4)) assertTrue(luminance(x, b.centerY) < 40)
                // The window's frame has a transparent 16 dp border around the card (dialog_background_material), so the
                // light background is read on the title's row, inside the card.
                assertTrue((title.bounds.left until title.bounds.right step 4).any { luminance(it, title.bounds.centerY) > 150 })
            } finally {
                image.recycle()
            }
            // With an area, such as look_closer's target, the picture holds only that area.
            val cropped = eyes.encode(capture, listOf(keep.bounds), emptyList(), area = title.bounds)
            val part = BitmapFactory.decodeByteArray(cropped.bytes, 0, cropped.bytes.size)
            try {
                assertEquals(title.bounds.width to title.bounds.height, part.width to part.height)
            } finally {
                part.recycle()
            }
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

    private companion object {
        // HiddenOverlays.MIN_MILLIS: with no overlay to wait for, the hide's last wait ends this long after it began.
        const val HIDE_MILLIS = 50L
    }
}
