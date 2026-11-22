package dev.kestrel.app.agent.eyes

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import dev.kestrel.agent.observe.SystemUiParts
import dev.kestrel.agent.port.Badge
import dev.kestrel.agent.port.Capture
import dev.kestrel.agent.port.EyesPort
import dev.kestrel.agent.port.OcrLine
import dev.kestrel.perception.OcrEngine
import dev.kestrel.perception.OcrFiles
import dev.kestrel.perception.OcrImage
import dev.kestrel.providers.chat.ImageType
import dev.kestrel.providers.chat.Part
import dev.kestrel.screen.ScreenSession
import dev.kestrel.screen.capture.CaptureResult
import dev.kestrel.screen.capture.ScreenCapture
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.ScreenTree
import dev.kestrel.voice.models.Engine
import dev.kestrel.voice.models.ModelFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The agent's eyes for `look_closer` (design D20 of sub-project 5): a screenshot of the app in front through the screen
 * session's [ScreenCapture], OCR with `:core:perception` on the regions that the agent names, and the JPEG for the model
 * with its masks and numbers. Screenshots exist only in memory: the agent closes each capture after its step, which
 * recycles the bitmap, and nothing here writes or logs one. An OCR failure goes to [report] by its classes only (D32 item
 * 48), and OCR then counts as unavailable for that call.
 */
internal class AppEyes(
    private val session: () -> ScreenSession?,
    private val models: () -> ModelFiles,
    private val closedScreens: () -> Set<String>,
    private val report: (String, Throwable) -> Unit,
) : EyesPort {
    /** A capture's pixels; [cyrillic] says that the window's tree text holds Cyrillic letters (D20's choice of recogniser). */
    private class Pixels(val bitmap: Bitmap, val cyrillic: Boolean) : Capture.Image {
        override val width: Int get() = bitmap.width
        override val height: Int get() = bitmap.height

        override fun close() = bitmap.recycle()

        override fun toString(): String = "Pixels(${width}x$height)"
    }

    override suspend fun capture(): Capture {
        val screen = session() ?: return Capture.NotNow(NO_SERVICE)
        val snapshot = screen.snapshots.current().snapshot
        val front = ScreenTree.frontWindow(snapshot) ?: return Capture.NotNow(NO_APP)
        if (front.packageName in closedScreens()) return Capture.NotNow(HOME)
        // A heads-up notification or the shade over the app (D9): its text must reach neither OCR nor a provider.
        if (snapshot.elements.any { it.packageName == SystemUiParts.SYSTEM_UI && SystemUiParts.DEFAULT.isNotification(it) }) {
            return Capture.NotNow(ScreenCapture.IN_THE_WAY)
        }
        return when (val result = screen.capture.capture(front.id)) {
            is CaptureResult.Image -> Capture.Captured(
                Pixels(result.bitmap, cyrillic(snapshot, front.id)), result.originX, result.originY, result.appPixels, result.blocked,
            )
            CaptureResult.Secure -> Capture.Secure
            is CaptureResult.NotNow -> Capture.NotNow(result.reason)
        }
    }

    override suspend fun read(capture: Capture.Captured, regions: List<IntRect>, exclude: List<IntRect>): List<OcrLine>? {
        val pixels = capture.image as? Pixels ?: return null
        return try {
            val files = files() ?: return null
            withContext(Dispatchers.Default) {
                // Password fields and data-sensitive elements are black before OCR sees the image (review B I12).
                val image = image(pixels.bitmap).blackedOut(exclude.map { it.inside(capture) })
                OcrEngine.open(files).use { engine ->
                    engine.read(image, regions.map { it.inside(capture) }, pixels.cyrillic).map { line ->
                        val b = line.bounds
                        OcrLine(line.text, IntRect(b.left + capture.originX, b.top + capture.originY, b.right + capture.originX, b.bottom + capture.originY), line.score)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report("OCR", e)
            null
        }
    }

    override suspend fun encode(capture: Capture.Captured, masks: List<IntRect>, marks: List<Badge>, area: IntRect?): Part.Image =
        withContext(Dispatchers.Default) {
            val pixels = (capture.image as Pixels).bitmap
            val whole = IntRect(0, 0, pixels.width, pixels.height)
            // With an area, only that part of the capture is in the picture (D20: a target limits the screenshot).
            val crop = if (area == null) whole else requireNotNull(area.inside(capture).intersect(whole)) { "The area is outside the capture" }
            val copy = createBitmap(crop.width, crop.height)
            try {
                val canvas = Canvas(copy)
                canvas.drawBitmap(pixels, -crop.left.toFloat(), -crop.top.toFloat(), null)
                // Masks and marks come in screen coordinates; the image starts at the capture's origin plus the crop's.
                canvas.translate(-(capture.originX + crop.left).toFloat(), -(capture.originY + crop.top).toFloat())
                val black = Paint().apply { color = Color.BLACK }
                for (mask in masks) canvas.drawRect(mask.left.toFloat(), mask.top.toFloat(), mask.right.toFloat(), mask.bottom.toFloat(), black)
                val shown = if (area == null) marks else marks.mapNotNull { mark -> mark.bounds.intersect(area)?.let { mark.copy(bounds = it) } }
                drawMarks(canvas, shown, max(copy.width, copy.height))
                val scale = minOf(1f, MAX_SIDE.toFloat() / max(copy.width, copy.height))
                val out = if (scale < 1f) {
                    Bitmap.createScaledBitmap(copy, max(1, (copy.width * scale).roundToInt()), max(1, (copy.height * scale).roundToInt()), true)
                } else {
                    copy
                }
                try {
                    val bytes = ByteArrayOutputStream()
                    out.compress(Bitmap.CompressFormat.JPEG, QUALITY, bytes)
                    Part.Image(ImageType.JPEG, bytes.toByteArray())
                } finally {
                    if (out !== copy) out.recycle()
                }
            } finally {
                copy.recycle()
            }
        }

    // Each number on a small label at its element's top-left, sized to stay readable after the image shrinks to 1,280.
    private fun drawMarks(canvas: Canvas, marks: List<Badge>, longer: Int) {
        val size = max(MIN_TEXT, TEXT * longer / MAX_SIDE)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size
            typeface = Typeface.DEFAULT_BOLD
        }
        val back = Paint().apply { color = MARK_COLOR }
        for (mark in marks) {
            val label = mark.number.toString()
            val left = mark.bounds.left.toFloat()
            val top = mark.bounds.top.toFloat()
            canvas.drawRect(left, top, left + text.measureText(label) + size / 2, top + size * 1.25f, back)
            canvas.drawText(label, left + size / 4, top + size, text)
        }
    }

    // The OCR files only when every OCR model is on the phone and intact; ModelFiles caches the hashes.
    private suspend fun files(): OcrFiles? {
        val files = models()
        val ocr = files.check().filter { it.model.engine == Engine.OCR }
        if (ocr.isEmpty() || !ocr.all { it.ready }) return null
        return withContext(Dispatchers.IO) { OcrFiles.under(files.root) }
    }

    // Called by look_closer with the lines that it keeps, after it has chosen the masks from all of read's lines.
    override fun once(lines: List<OcrLine>): List<OcrLine> = Companion.once(lines)

    private fun IntRect.inside(capture: Capture.Captured) =
        IntRect(left - capture.originX, top - capture.originY, right - capture.originX, bottom - capture.originY)

    override fun toString(): String = "AppEyes"

    companion object {
        const val NO_SERVICE = "Kestrel's accessibility service is not running."
        const val NO_APP = "No app is in front."
        const val HOME = "Kestrel takes no screenshot of the home screen or the recent apps."
        const val MAX_SIDE = 1280
        const val QUALITY = 80
        private const val TEXT = 32f
        private const val MIN_TEXT = 24f
        private const val MARK_COLOR = 0xE61565C0.toInt()

        /** [bitmap]'s pixels for OCR. */
        fun image(bitmap: Bitmap): OcrImage {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return OcrImage(bitmap.width, bitmap.height, pixels)
        }

        /**
         * [lines] with each line that two partly overlapping regions both read given once. Two lines are the same when
         * more than half of one's box lies inside the other's and its text is part of the other's (a region's edge can
         * cut a line); the longer text stays, the higher score on a tie, and the order is kept.
         */
        fun once(lines: List<OcrLine>): List<OcrLine> {
            val kept = mutableListOf<Int>()
            val order = lines.indices.sortedWith(compareByDescending<Int> { lines[it].text.length }.thenByDescending { lines[it].score })
            for (index in order) {
                if (kept.none { covers(lines[it], lines[index]) }) kept += index
            }
            return kept.sorted().map { lines[it] }
        }

        private fun covers(line: OcrLine, other: OcrLine): Boolean {
            if (other.text !in line.text) return false
            val common = line.bounds.intersect(other.bounds) ?: return false
            return 2L * common.width * common.height > other.bounds.width.toLong() * other.bounds.height
        }

        /** Whether the tree text of the window [windowId] holds a Cyrillic letter. */
        fun cyrillic(snapshot: ScreenSnapshot, windowId: Int): Boolean = snapshot.elements.any { element ->
            element.windowId == windowId && element.label?.any { Character.UnicodeBlock.of(it) == Character.UnicodeBlock.CYRILLIC } == true
        }

        /**
         * The packages of the home screen and of the recent apps (Android's `config_recentsComponentName`), whose screens
         * Kestrel never captures (design D20 of sub-project 5). Android publishes the recent apps' component only as this
         * internal resource, which is why the lookup by name is used.
         */
        @SuppressLint("DiscouragedApi")
        fun closedScreens(context: Context): Set<String> {
            val home = context.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            )?.activityInfo?.packageName
            val id = Resources.getSystem().getIdentifier("config_recentsComponentName", "string", "android")
            val recents = if (id == 0) null else Resources.getSystem().getString(id).substringBefore('/').takeIf { it.isNotEmpty() }
            return setOfNotNull(home, recents)
        }
    }
}
