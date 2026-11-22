package dev.kestrel.perception

import org.junit.Assert.assertEquals
import org.junit.Test

/** The detector's and the recogniser's inputs against the Python reference's preprocessing of the same test images. */
class ImageInputTest {
    private val reference = Reference.lines("reference.txt").map { it.split(' ') }

    @Test
    fun theDetectorsInputSizeFollowsTheReferenceAndEnlargesARegionAtMostTwice() {
        val sizes = reference.filter { it[0] == "detector-size" }
        assertEquals(3, sizes.size)
        for (size in sizes) {
            assertEquals(size[3].toInt() to size[4].toInt(), Detector.inputSize(size[1].toInt(), size[2].toInt()))
        }
        // Kestrel's own rule for regions smaller than the limit (the reference never enlarges): 500 × 300 grows 1.92
        // times; 100 × 40 grows twice to 200 × 80, whose 80 rounds half to even, to 64.
        assertEquals(960 to 576, Detector.inputSize(500, 300))
        assertEquals(192 to 64, Detector.inputSize(100, 40))
        assertEquals(704 to 1280, Detector.inputSize(720, 1280, limit = 1280))
    }

    @Test
    fun theDetectorsInputHasTheReferencesValuesInBlueGreenRedOrder() {
        val (width, height) = Detector.inputSize(1080, 2400)
        val input = Detector.input(Reference.synthetic(1080, 2400).scaled(width, height))
        val values = reference.filter { it[0] == "detector-value" }
        assertEquals(12, values.size)
        for (value in values) {
            val (channel, y, x) = value.subList(3, 6).map(String::toInt)
            // One step of a pixel's value is 1/255/0.224, about 0.018.
            assertEquals(value.joinToString(" "), value[6].toDouble(), input[channel * width * height + y * width + x].toDouble(), 0.02)
        }
    }

    @Test
    fun theRecognisersInputHasTheReferencesWidthsAndValues() {
        for (size in reference.filter { it[0] == "recogniser-size" }) {
            assertEquals(size.joinToString(" "), size[3].toInt(), Recogniser.input(Reference.synthetic(size[1].toInt(), size[2].toInt())).width)
        }
        val values = reference.filter { it[0] == "recogniser-value" }
        assertEquals(11, values.size)
        for (value in values) {
            val input = Recogniser.input(Reference.synthetic(value[1].toInt(), value[2].toInt()))
            val (channel, y, x) = value.subList(3, 6).map(String::toInt)
            // One step of a pixel's value is 2/255, about 0.008.
            assertEquals(value.joinToString(" "), value[6].toDouble(), input.values[channel * 48 * input.width + y * input.width + x].toDouble(), 0.01)
        }
        // A very long line is cut to 3200 columns.
        assertEquals(Recogniser.MAX_WIDTH, Recogniser.input(Reference.synthetic(2000, 10)).width)
    }
}
