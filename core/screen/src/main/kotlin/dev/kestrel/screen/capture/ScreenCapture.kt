package dev.kestrel.screen.capture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.Display
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.overlay.HiddenOverlays
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/** What a screenshot of the app in front gave (design D20 of sub-project 5). */
sealed interface CaptureResult {
    /**
     * The app window's pixels, which exist only in memory: [bitmap] starts at ([originX], [originY]) on the screen,
     * [appPixels] are the app's areas that no other window covers and [blocked] the areas painted black, both in screen
     * coordinates. The caller recycles [bitmap].
     */
    class Image(val bitmap: Bitmap, val originX: Int, val originY: Int, val appPixels: List<IntRect>, val blocked: List<IntRect>) : CaptureResult {
        override fun toString(): String = "Image(${bitmap.width}x${bitmap.height}, blocked=${blocked.size})"
    }

    /** The app blocks screenshots: Android refused the capture, or the app's own pixels are black. */
    data object Secure : CaptureResult

    /** No screenshot now; [reason] is one of [ScreenCapture]'s fixed sentences. */
    data class NotNow(val reason: String) : CaptureResult
}

/**
 * Screenshots of one app window for the agent's `look_closer` (design D20 of sub-project 5; r49). From API 34 Android
 * captures the window alone (`takeScreenshotOfWindow`), so no other window and none of Kestrel's overlays is in it, and a
 * secure window gives error 6. On API 33 (the A71) only the whole display can be captured: Kestrel's overlays are made
 * transparent first, the image is cropped to the window, and every part that a window above it or a visible overlay of
 * Kestrel's covers is painted black. On every level a window whose own pixels are at least 99 % pure black counts as
 * secure. Android's window list is read before and after each capture; when it changed, the capture is dropped and tried
 * once more. At most one capture runs per 400 ms. Bitmaps live only in memory and are never written anywhere.
 */
class ScreenCapture internal constructor(
    private val service: AccessibilityService,
    private val hideOverlays: suspend () -> HiddenOverlays?,
) {
    private val lock = Mutex()
    private var lastMillis = 0L

    /** One window's place in Android's window list. */
    private data class Shape(val id: Int, val type: Int, val layer: Int, val bounds: IntRect)

    /**
     * A screenshot of the window [windowId]. [wholeDisplay] chooses the capture of the whole display, which API 33 needs
     * and which the device tests also run on later levels.
     */
    suspend fun capture(windowId: Int, wholeDisplay: Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE): CaptureResult =
        lock.withLock {
            withContext(Dispatchers.Main) {
                repeat(2) {
                    val before = shapes()
                    val target = before.firstOrNull { it.id == windowId } ?: return@withContext CaptureResult.NotNow(GONE)
                    val wait = lastMillis + INTERVAL_MILLIS - SystemClock.uptimeMillis()
                    if (wait > 0) delay(wait)
                    val shot = if (wholeDisplay || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        displayShot(target, before)
                    } else {
                        windowShot(target)
                    }
                    if (shot !is CaptureResult.Image) return@withContext shot
                    // A window that came, went or moved during the capture, such as a notification, may be in it.
                    if (shapes() == before) return@withContext shot
                    shot.bitmap.recycle()
                }
                CaptureResult.NotNow(IN_THE_WAY)
            }
        }

    private fun shapes(): List<Shape> = service.windows.map { window ->
        val bounds = Rect().also(window::getBoundsInScreen)
        Shape(window.id, window.type, window.layer, IntRect(bounds.left, bounds.top, bounds.right, bounds.bottom))
    }

    // Its only caller checks the level; :core:screen has no androidx.annotation for @RequiresApi.
    @SuppressLint("UseRequiresApi")
    @TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private suspend fun windowShot(target: Shape): CaptureResult {
        val result = when (val shot = shoot { executor, callback -> service.takeScreenshotOfWindow(target.id, executor, callback) }) {
            is Shot.Taken -> shot.result
            is Shot.Refused -> return shot.result
        }
        val bitmap = toBitmap(result) ?: return CaptureResult.NotNow(FAILED)
        // A window with a shadow, such as a dialog, has the same margin around its bounds on each side of its image.
        val originX = target.bounds.left - maxOf(0, bitmap.width - target.bounds.width) / 2
        val originY = target.bounds.top - maxOf(0, bitmap.height - target.bounds.height) / 2
        return finish(bitmap, originX, originY, listOf(IntRect(0, 0, bitmap.width, bitmap.height)), emptyList())
    }

    private suspend fun displayShot(target: Shape, windows: List<Shape>): CaptureResult {
        val hidden = hideOverlays() ?: return CaptureResult.NotNow(FAILED)
        val shot = try {
            shoot { executor, callback -> service.takeScreenshot(Display.DEFAULT_DISPLAY, executor, callback) }
        } finally {
            hidden.close()
        }
        val result = when (shot) {
            is Shot.Taken -> shot.result
            is Shot.Refused -> return shot.result
        }
        val display = toBitmap(result) ?: return CaptureResult.NotNow(FAILED)
        val area = target.bounds.intersect(IntRect(0, 0, display.width, display.height))
        if (area == null) {
            display.recycle()
            return CaptureResult.NotNow(GONE)
        }
        // Every window above the app's (the bars, the keyboard, other apps' dialogs, the shade) and every overlay of
        // Kestrel's that was visible, in the crop's coordinates.
        val covers = windows.filter { it.layer > target.layer }.map { it.bounds } + hidden.bounds
        val blocked = covers.mapNotNull { it.intersect(area) }
            .map { IntRect(it.left - area.left, it.top - area.top, it.right - area.left, it.bottom - area.top) }
        val crop = withContext(Dispatchers.Default) {
            Bitmap.createBitmap(area.width, area.height, Bitmap.Config.ARGB_8888).also { crop ->
                val canvas = Canvas(crop)
                canvas.drawBitmap(display, -area.left.toFloat(), -area.top.toFloat(), null)
                val black = Paint().apply { color = BLACK }
                for (rect in blocked) canvas.drawRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(), black)
            }
        }
        display.recycle()
        return finish(crop, area.left, area.top, uncovered(IntRect(0, 0, area.width, area.height), blocked), blocked)
    }

    // The black check on the app's own pixels (r49: a window that is secure only by policy is black without an error).
    private suspend fun finish(bitmap: Bitmap, originX: Int, originY: Int, appPixels: List<IntRect>, blocked: List<IntRect>): CaptureResult {
        val black = withContext(Dispatchers.Default) {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            mostlyBlack(pixels, bitmap.width, appPixels)
        }
        if (black) {
            bitmap.recycle()
            return CaptureResult.Secure
        }
        fun IntRect.onScreen() = IntRect(left + originX, top + originY, right + originX, bottom + originY)
        return CaptureResult.Image(bitmap, originX, originY, appPixels.map { it.onScreen() }, blocked.map { it.onScreen() })
    }

    private sealed interface Shot {
        class Taken(val result: ScreenshotResult) : Shot

        class Refused(val result: CaptureResult) : Shot
    }

    // Android's error 3 (too soon after the last screenshot) is tried once more after 350 ms.
    private suspend fun shoot(take: (Executor, TakeScreenshotCallback) -> Unit): Shot {
        val first = shootOnce(take)
        if (first !is Shot.Refused || first.result != TOO_SOON) return first
        delay(RETRY_MILLIS)
        val second = shootOnce(take)
        return if (second is Shot.Refused && second.result == TOO_SOON) Shot.Refused(CaptureResult.NotNow(FAILED)) else second
    }

    private suspend fun shootOnce(take: (Executor, TakeScreenshotCallback) -> Unit): Shot {
        lastMillis = SystemClock.uptimeMillis()
        return suspendCancellableCoroutine { continuation ->
            take(service.mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    if (continuation.isActive) continuation.resume(Shot.Taken(result)) else result.hardwareBuffer.close()
                }

                override fun onFailure(errorCode: Int) {
                    if (continuation.isActive) continuation.resume(Shot.Refused(failure(errorCode)))
                }
            })
        }
    }

    private fun failure(errorCode: Int): CaptureResult = when (errorCode) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> CaptureResult.Secure
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> TOO_SOON
        else -> CaptureResult.NotNow(FAILED)
    }

    // The hardware buffer is copied into an ordinary bitmap and closed at once (r49: about 10 MiB for the A71's display).
    private suspend fun toBitmap(result: ScreenshotResult): Bitmap? = withContext(Dispatchers.Default) {
        result.hardwareBuffer.use { buffer ->
            val wrapped = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return@withContext null
            try {
                wrapped.copy(Bitmap.Config.ARGB_8888, false)
            } finally {
                wrapped.recycle()
            }
        }
    }

    override fun toString(): String = "ScreenCapture"

    companion object {
        const val INTERVAL_MILLIS = 400L
        const val RETRY_MILLIS = 350L
        const val FAILED = "The screenshot failed."
        const val IN_THE_WAY = "A notification is in the way; try again in a moment."
        const val GONE = "The app's window is gone."
        private val TOO_SOON = CaptureResult.NotNow("too soon")
        private const val BLACK = 0xFF000000.toInt()

        /** [area] without [holes], as rectangles that do not overlap. */
        fun uncovered(area: IntRect, holes: List<IntRect>): List<IntRect> = holes.fold(listOf(area)) { parts, hole ->
            parts.flatMap { part ->
                val overlap = part.intersect(hole) ?: return@flatMap listOf(part)
                listOf(
                    IntRect(part.left, part.top, part.right, overlap.top),
                    IntRect(part.left, overlap.bottom, part.right, part.bottom),
                    IntRect(part.left, overlap.top, overlap.left, overlap.bottom),
                    IntRect(overlap.right, overlap.top, part.right, overlap.bottom),
                ).filterNot { it.isEmpty }
            }
        }

        /**
         * Whether at least 99 % of the pixels in [parts] of an image [width] pixels wide are pure black (red, green and
         * blue 0), as a secure window is in a screenshot; dark themes are rarely pure black. No pixels count as not black.
         */
        fun mostlyBlack(pixels: IntArray, width: Int, parts: List<IntRect>): Boolean {
            var all = 0L
            var black = 0L
            for (part in parts) {
                for (y in part.top until part.bottom) {
                    for (x in part.left until part.right) {
                        all++
                        if ((pixels[y * width + x] and 0xFFFFFF) == 0) black++
                    }
                }
            }
            return all > 0 && black * 100 >= all * 99
        }
    }
}
