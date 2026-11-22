package dev.kestrel.perception

import dev.kestrel.screen.model.IntRect
import kotlin.math.max
import kotlin.math.min

/** A text line's box in the source image, with the mean of the detector's map inside it. */
data class TextBox(val bounds: IntRect, val score: Double)

/**
 * The detector's post-processing (design D20 of sub-project 5; r48): the probability map thresholded at 0.3, its
 * connected parts (8 neighbours, at most 1000) as axis-aligned boxes, because text on a phone's screen is horizontal; a
 * box whose shorter side is below 3 or whose mean score is below 0.6 is dropped; the rest grow by the unclip distance
 * (area × 1.5 ÷ perimeter), must then have a shorter side of at least 5, and are scaled to the source image. This keeps
 * the geometry in Kotlin, without OpenCV or another native library (r48, Fit 5).
 */
object TextBoxes {
    const val THRESHOLD = 0.3
    const val BOX_THRESHOLD = 0.6
    const val UNCLIP_RATIO = 1.5
    const val MAX_CANDIDATES = 1000
    private const val MIN_SIDE = 3

    /**
     * The boxes of [map] ([mapWidth] x [mapHeight] values, row by row) in a source image of [sourceWidth] x
     * [sourceHeight] pixels, from top to bottom and left to right.
     */
    fun find(map: FloatArray, mapWidth: Int, mapHeight: Int, sourceWidth: Int, sourceHeight: Int): List<TextBox> {
        require(map.size == mapWidth * mapHeight) { "Expected $mapWidth x $mapHeight values" }
        val seen = BooleanArray(map.size)
        val stack = IntArray(map.size)
        val boxes = mutableListOf<TextBox>()
        var candidates = 0
        for (start in map.indices) {
            if (seen[start] || map[start] <= THRESHOLD) continue
            if (candidates == MAX_CANDIDATES) break
            candidates++
            // One connected part, found with an explicit stack.
            var left = mapWidth
            var top = mapHeight
            var right = -1
            var bottom = -1
            var sum = 0.0
            var count = 0
            var size = 0
            stack[size++] = start
            seen[start] = true
            while (size > 0) {
                val at = stack[--size]
                val x = at % mapWidth
                val y = at / mapWidth
                sum += map[at]
                count++
                left = min(left, x)
                right = max(right, x)
                top = min(top, y)
                bottom = max(bottom, y)
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= mapHeight) continue
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx < 0 || nx >= mapWidth) continue
                        val next = ny * mapWidth + nx
                        if (!seen[next] && map[next] > THRESHOLD) {
                            seen[next] = true
                            stack[size++] = next
                        }
                    }
                }
            }
            box(sum / count, mapWidth, mapHeight, left, top, right, bottom, sourceWidth, sourceHeight)?.let { boxes += it }
        }
        return boxes.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
    }

    // The part's box in the map runs between the centres of its outer pixels, as OpenCV's minAreaRect measures it.
    private fun box(
        score: Double, mapWidth: Int, mapHeight: Int, left: Int, top: Int, right: Int, bottom: Int,
        sourceWidth: Int, sourceHeight: Int,
    ): TextBox? {
        val width = (right - left).toDouble()
        val height = (bottom - top).toDouble()
        if (min(width, height) < MIN_SIDE) return null
        if (score < BOX_THRESHOLD) return null
        val distance = width * height * UNCLIP_RATIO / (2 * (width + height))
        if (min(width, height) + 2 * distance < MIN_SIDE + 2) return null
        fun scale(value: Double, from: Int, to: Int): Int = Math.rint(value / from * to).toInt().coerceIn(0, to)
        // As the reference does, the corners are kept inside the image, and boxes of 3 pixels or less go.
        val bounds = IntRect(
            min(scale(left - distance, mapWidth, sourceWidth), sourceWidth - 1),
            min(scale(top - distance, mapHeight, sourceHeight), sourceHeight - 1),
            min(scale(right + distance, mapWidth, sourceWidth), sourceWidth - 1),
            min(scale(bottom + distance, mapHeight, sourceHeight), sourceHeight - 1),
        )
        if (bounds.width <= MIN_SIDE || bounds.height <= MIN_SIDE) return null
        return TextBox(bounds, score)
    }
}
