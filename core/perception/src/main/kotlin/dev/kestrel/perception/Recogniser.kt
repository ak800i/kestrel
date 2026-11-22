package dev.kestrel.perception

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * One of PP-OCRv5's mobile text recognisers, `latin` or `cyrillic` (design D20 of sub-project 5; r48), with its
 * [dictionary]. Opening it fails when the model's class count is not the dictionary's lines plus two.
 */
class Recogniser internal constructor(private val session: OrtSession, val dictionary: Dictionary) : AutoCloseable {
    init {
        dictionary.check((session.outputInfo.values.first().info as TensorInfo).shape.last())
    }

    /** What the recogniser reads in [image], one text line. */
    fun read(image: OcrImage): Reading {
        val input = input(image)
        val environment = OrtEnvironment.getEnvironment()
        val shape = longArrayOf(1, 3, HEIGHT.toLong(), input.width.toLong())
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input.values), shape).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                val output = result[0] as OnnxTensor
                val steps = output.info.shape[1].toInt()
                val scores = FloatArray(steps * dictionary.classes)
                output.floatBuffer.get(scores)
                return CtcDecoder.decode(scores, steps, dictionary)
            }
        }
    }

    override fun close() = session.close()

    override fun toString(): String = "Recogniser($dictionary)"

    /** The recogniser's input: [width] columns of 48 rows in blue, green and red planes. */
    class Input(val width: Int, val values: FloatArray)

    companion object {
        const val HEIGHT = 48
        const val MIN_WIDTH = 320
        const val MAX_WIDTH = 3200

        /**
         * [image] scaled to a height of 48 and its own width, padded on the right with zeros to 48 × max(320/48, w/h)
         * columns (at most 3200), each channel normalised to −1..1.
         */
        fun input(image: OcrImage): Input {
            val ratio = image.width.toDouble() / image.height
            val width = min(MAX_WIDTH, (HEIGHT * max(MIN_WIDTH.toDouble() / HEIGHT, ratio)).toInt())
            val resized = image.scaled(min(width, ceil(HEIGHT * ratio).toInt()), HEIGHT)
            val plane = HEIGHT * width
            val values = FloatArray(3 * plane)
            for (y in 0 until HEIGHT) {
                for (x in 0 until resized.width) {
                    val pixel = resized.pixels[y * resized.width + x]
                    val at = y * width + x
                    values[at] = ((pixel and 0xFF) / 255f - 0.5f) / 0.5f
                    values[plane + at] = (((pixel shr 8) and 0xFF) / 255f - 0.5f) / 0.5f
                    values[2 * plane + at] = (((pixel shr 16) and 0xFF) / 255f - 0.5f) / 0.5f
                }
            }
            return Input(width, values)
        }
    }
}
