package dev.kestrel.perception

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * PP-OCRv5's mobile text detector (design D20 of sub-project 5; r48). [boxes] scales the image so that its longer side
 * is at most [LIMIT] pixels, or enlarges a small image (a region) by at most [MAX_UPSCALE], rounds each side to a
 * multiple of 32, and finds the text lines in the detector's probability map with [TextBoxes].
 */
class Detector internal constructor(private val session: OrtSession) : AutoCloseable {
    fun boxes(image: OcrImage, limit: Int = LIMIT): List<TextBox> {
        val (width, height) = inputSize(image.width, image.height, limit)
        val environment = OrtEnvironment.getEnvironment()
        val shape = longArrayOf(1, 3, height.toLong(), width.toLong())
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input(image.scaled(width, height))), shape).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                val output = result[0] as OnnxTensor
                val mapShape = output.info.shape
                val mapHeight = mapShape[2].toInt()
                val mapWidth = mapShape[3].toInt()
                val map = FloatArray(mapWidth * mapHeight)
                output.floatBuffer.get(map)
                return TextBoxes.find(map, mapWidth, mapHeight, image.width, image.height)
            }
        }
    }

    override fun close() = session.close()

    override fun toString(): String = "Detector"

    companion object {
        const val LIMIT = 960
        const val MAX_UPSCALE = 2.0

        // ImageNet's mean and standard deviation, applied to the channels in BGR order, as the reference does.
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

        /** The detector's input size for an image of [width] x [height]: (width, height), each a multiple of 32. */
        fun inputSize(width: Int, height: Int, limit: Int = LIMIT): Pair<Int, Int> {
            val ratio = min(MAX_UPSCALE, limit.toDouble() / max(width, height))
            fun side(value: Int): Int = max(32, Math.rint((value * ratio).toInt() / 32.0).toInt() * 32)
            return side(width) to side(height)
        }

        /** [image] as the detector's input: blue, green and red planes, each scaled by 1/255 and normalised. */
        fun input(image: OcrImage): FloatArray {
            val plane = image.width * image.height
            val values = FloatArray(3 * plane)
            for (i in 0 until plane) {
                val pixel = image.pixels[i]
                values[i] = ((pixel and 0xFF) / 255f - MEAN[0]) / STD[0]
                values[plane + i] = (((pixel shr 8) and 0xFF) / 255f - MEAN[1]) / STD[1]
                values[2 * plane + i] = (((pixel shr 16) and 0xFF) / 255f - MEAN[2]) / STD[2]
            }
            return values
        }
    }
}
