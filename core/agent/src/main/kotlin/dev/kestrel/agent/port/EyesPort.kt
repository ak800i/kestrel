package dev.kestrel.agent.port

import dev.kestrel.providers.chat.Part
import dev.kestrel.screen.model.IntRect

/**
 * Screenshots and OCR for `look_closer` (design D20 of sub-project 5). Plan 5C's `AppEyes` implements it; until then
 * `:app` passes [NoEyes].
 */
interface EyesPort {
    suspend fun capture(): Capture

    /** The OCR lines in [regions] of the capture, with [exclude] blacked out first; null when OCR is unavailable. */
    suspend fun read(capture: Capture.Captured, regions: List<IntRect>, exclude: List<IntRect>): List<OcrLine>?

    /** The capture as a JPEG for the model, with [masks] blacked out and the [marks] drawn on it. */
    suspend fun encode(capture: Capture.Captured, masks: List<IntRect>, marks: List<Badge>): Part.Image

    /**
     * The OCR lines that `look_closer` keeps for the observation, with each line that overlapping regions both read given
     * once. The masks use every line of [read], so a line left out here is still masked.
     */
    fun once(lines: List<OcrLine>): List<OcrLine> = lines
}

/** No screenshots and no OCR: what `:app` uses until plan 5C. */
object NoEyes : EyesPort {
    override suspend fun capture(): Capture = Capture.NotNow("unavailable")

    override suspend fun read(capture: Capture.Captured, regions: List<IntRect>, exclude: List<IntRect>): List<OcrLine>? = null

    override suspend fun encode(capture: Capture.Captured, masks: List<IntRect>, marks: List<Badge>): Part.Image =
        throw IllegalStateException("No screenshots without eyes")
}

sealed interface Capture {
    /** The captured pixels, which exist only in memory; [close] frees them. */
    interface Image : AutoCloseable {
        val width: Int
        val height: Int
    }

    /**
     * A capture whose top-left lies at ([originX], [originY]) on the screen; [appPixels] are the app's own areas that no
     * other window covers, and [blocked] the areas that are blacked out.
     */
    data class Captured(val image: Image, val originX: Int, val originY: Int, val appPixels: List<IntRect>, val blocked: List<IntRect>) : Capture {
        override fun toString(): String = "Captured(${image.width}x${image.height}, appAreas=${appPixels.size}, blocked=${blocked.size})"
    }

    /** The app blocks screenshots. */
    data object Secure : Capture

    /** No capture now; [reason] is one of Kestrel's fixed sentences. */
    data class NotNow(val reason: String) : Capture
}

/** A line that OCR read, in screen coordinates, with its mean character score. */
data class OcrLine(val text: String, val bounds: IntRect, val score: Double) {
    override fun toString(): String = "OcrLine(text=${text.length} chars, score=$score)"
}
