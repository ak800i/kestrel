package dev.kestrel.perception

import dev.kestrel.screen.model.IntRect
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * An image as ARGB pixels, row by row, as Android's `Bitmap.getPixels` gives them. It exists only in memory; its
 * [toString] prints only its size.
 */
class OcrImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(width > 0 && height > 0 && pixels.size == width * height) { "Expected $width x $height pixels" }
    }

    /** The part of the image inside [rect], which must lie inside the image. */
    fun crop(rect: IntRect): OcrImage {
        require(rect.left >= 0 && rect.top >= 0 && rect.right <= width && rect.bottom <= height && !rect.isEmpty) { "Crop outside the image" }
        val out = IntArray(rect.width * rect.height)
        for (y in 0 until rect.height) {
            System.arraycopy(pixels, (rect.top + y) * width + rect.left, out, y * rect.width, rect.width)
        }
        return OcrImage(rect.width, rect.height, out)
    }

    /** A copy with every part of [rects] painted opaque black (design D20: password fields never reach OCR). */
    fun blackedOut(rects: List<IntRect>): OcrImage {
        val out = pixels.copyOf()
        for (rect in rects) {
            val part = rect.intersect(IntRect(0, 0, width, height)) ?: continue
            for (y in part.top until part.bottom) out.fill(BLACK, y * width + part.left, y * width + part.right)
        }
        return OcrImage(width, height, out)
    }

    /**
     * The image scaled to [newWidth] x [newHeight] by bilinear interpolation with OpenCV's `INTER_LINEAR` rule, which the
     * Python reference uses: pixel centres at half pixels, edges clamped, each channel rounded to a whole value.
     */
    fun scaled(newWidth: Int, newHeight: Int): OcrImage {
        if (newWidth == width && newHeight == height) return this
        val xs = Axis(width, newWidth)
        val ys = Axis(height, newHeight)
        val out = IntArray(newWidth * newHeight)
        for (y in 0 until newHeight) {
            val row0 = ys.low[y] * width
            val row1 = ys.high[y] * width
            val wy = ys.weight[y]
            for (x in 0 until newWidth) {
                val x0 = xs.low[x]
                val x1 = xs.high[x]
                val wx = xs.weight[x]
                out[y * newWidth + x] = blend(pixels[row0 + x0], pixels[row0 + x1], pixels[row1 + x0], pixels[row1 + x1], wx, wy)
            }
        }
        return OcrImage(newWidth, newHeight, out)
    }

    override fun toString(): String = "OcrImage(${width}x$height)"

    // For each output position: the two source positions and the weight of the second.
    private class Axis(source: Int, target: Int) {
        val low = IntArray(target)
        val high = IntArray(target)
        val weight = FloatArray(target)

        init {
            val scale = source.toDouble() / target
            for (i in 0 until target) {
                val position = (i + 0.5) * scale - 0.5
                var start = floor(position).toInt()
                var fraction = (position - start).toFloat()
                if (start < 0) {
                    start = 0
                    fraction = 0f
                }
                if (start >= source - 1) {
                    start = source - 1
                    fraction = 0f
                }
                low[i] = start
                high[i] = minOf(start + 1, source - 1)
                weight[i] = fraction
            }
        }
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()

        fun blend(p00: Int, p01: Int, p10: Int, p11: Int, wx: Float, wy: Float): Int {
            fun channel(shift: Int): Int {
                val top = ((p00 shr shift) and 0xFF) * (1 - wx) + ((p01 shr shift) and 0xFF) * wx
                val bottom = ((p10 shr shift) and 0xFF) * (1 - wx) + ((p11 shr shift) and 0xFF) * wx
                return (top * (1 - wy) + bottom * wy).roundToInt().coerceIn(0, 255)
            }
            return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
    }
}
