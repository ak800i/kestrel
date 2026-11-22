package dev.kestrel.perception

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import dev.kestrel.screen.model.IntRect

/** A line that OCR read, in the coordinates of the image that it was given, with its mean character score. */
data class OcrText(val text: String, val bounds: IntRect, val score: Double) {
    override fun toString(): String = "OcrText(text=${text.length} chars, score=$score)"
}

/**
 * Kestrel's OCR (design D20 of sub-project 5; r48): PP-OCRv5's mobile detector and its `latin` and `cyrillic`
 * recognisers through ONNX Runtime, on the CPU with [open]'s threads. Each box is read by the Latin recogniser first;
 * when its mean score is below 0.8, or when the screen's text holds Cyrillic letters, the Cyrillic recogniser reads it
 * too, and the higher mean score wins; a Latin reading of at least 0.8 gives way only to a reading with a Cyrillic letter.
 * The work is blocking and CPU-bound, so callers run it off the main thread, one call at a time.
 */
class OcrEngine private constructor(
    private val detector: Detector,
    private val latin: Recogniser,
    private val cyrillic: Recogniser,
) : AutoCloseable {
    /**
     * The lines in [regions] of [image], each region read on its own; a region inside another one is skipped, and the
     * lines come in reading order. [cyrillicNearby] says that the screen's text holds Cyrillic letters.
     */
    fun read(image: OcrImage, regions: List<IntRect>, cyrillicNearby: Boolean = false, limit: Int = Detector.LIMIT): List<OcrText> {
        val whole = IntRect(0, 0, image.width, image.height)
        val parts = regions.mapNotNull { it.intersect(whole) }.distinct()
        val outer = parts.filter { part -> parts.none { other -> other != part && other.contains(part) } }
        return outer.flatMap { region ->
            val crop = image.crop(region)
            detect(crop, limit).mapNotNull { box ->
                recognise(crop, box, cyrillicNearby)?.let { line ->
                    val bounds = line.bounds
                    line.copy(bounds = IntRect(bounds.left + region.left, bounds.top + region.top, bounds.right + region.left, bounds.bottom + region.top))
                }
            }
        }.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
    }

    /** The text boxes of [image] (the device tests time detection on its own). */
    fun detect(image: OcrImage, limit: Int = Detector.LIMIT): List<TextBox> = detector.boxes(image, limit)

    /** The text of [box] in [image], or null when no recogniser read anything but spaces there. */
    fun recognise(image: OcrImage, box: TextBox, cyrillicNearby: Boolean = false): OcrText? {
        val crop = image.crop(box.bounds)
        val best = choose(latin.read(crop), cyrillicNearby) { cyrillic.read(crop) }
        val text = best.text.trim()
        return if (text.isEmpty()) null else OcrText(text, box.bounds, best.score)
    }

    override fun close() {
        detector.close()
        latin.close()
        cyrillic.close()
    }

    override fun toString(): String = "OcrEngine"

    companion object {
        /** Below this mean score of the Latin recogniser, the Cyrillic one reads the box too. */
        const val CYRILLIC_BELOW = 0.8

        /**
         * Loads the three models with [threads] threads each (two by default; the device tests measure two and four).
         * Fails when a file is missing or a recogniser does not fit its dictionary.
         */
        fun open(files: OcrFiles, threads: Int = 2): OcrEngine {
            val environment = OrtEnvironment.getEnvironment()
            val opened = mutableListOf<AutoCloseable>()
            try {
                OrtSession.SessionOptions().use { options ->
                    options.setIntraOpNumThreads(threads)
                    options.setInterOpNumThreads(1)
                    fun session(file: java.io.File): OrtSession = environment.createSession(file.path, options).also { opened += it }
                    val detector = Detector(session(files.detector))
                    val latin = Recogniser(session(files.latin), Dictionary.parse(files.latinDictionary.readText()))
                    val cyrillic = Recogniser(session(files.cyrillic), Dictionary.parse(files.cyrillicDictionary.readText()))
                    return OcrEngine(detector, latin, cyrillic)
                }
            } catch (e: Exception) {
                opened.forEach { runCatching { it.close() } }
                throw e
            }
        }

        /**
         * The reading that counts for a box: [latin]'s, unless its score is below [CYRILLIC_BELOW] or [cyrillicNearby]
         * is true; then [cyrillic] reads the box too and the higher score wins, the Latin reading on a tie. A Latin
         * reading of at least [CYRILLIC_BELOW] gives way only to a reading with a Cyrillic letter, because the Cyrillic
         * dictionary lacks č, ć, š, ž and đ and reads Serbian Latin text without them, with a higher score. A reading of
         * only spaces counts as none, because the Latin recogniser reads Cyrillic text as spaces with a high score.
         */
        internal fun choose(latin: Reading, cyrillicNearby: Boolean, cyrillic: () -> Reading): Reading {
            val first = latin.counted()
            if (first.score >= CYRILLIC_BELOW && !cyrillicNearby) return first
            val second = cyrillic().counted()
            if (first.score >= CYRILLIC_BELOW && second.text.none { Character.UnicodeBlock.of(it) == Character.UnicodeBlock.CYRILLIC }) return first
            return if (second.score > first.score) second else first
        }

        private fun Reading.counted(): Reading = if (text.isBlank()) Reading("", 0.0) else this
    }
}
