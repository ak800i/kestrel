package dev.kestrel.perception

import dev.kestrel.screen.model.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The detector's post-processing against the reference's, on the probability maps that the detector gave for two
 * fixture screens (720 × 1280, so 544 × 960 maps), saved at a quarter of their size and in ten steps.
 */
class TextBoxesTest {
    private class Map(val name: String, val width: Int, val height: Int, val values: FloatArray, val boxes: List<IntRect>)

    // "map <name> <width> <height>", then one line per row of runs "<step>*<count>" or "<step>" (the value is step/9),
    // then the reference's boxes "box <left> <top> <right> <bottom>" in the 720 × 1280 screen.
    private val maps: List<Map> = Reference.lines("maps.txt").let { lines ->
        lines.indices.filter { lines[it].startsWith("map ") }.map { at ->
            val (_, name, width, height) = lines[at].split(' ')
            val w = width.toInt()
            val h = height.toInt()
            val values = FloatArray(w * 4 * h * 4)
            for (row in 0 until h) {
                var x = 0
                for (run in lines[at + 1 + row].split(' ')) {
                    val parts = run.split('*')
                    val value = parts[0].toInt() / 9f
                    repeat(if (parts.size == 2) parts[1].toInt() else 1) {
                        // Each saved value stands for 4 × 4 values of the map.
                        for (dy in 0 until 4) for (dx in 0 until 4) values[(row * 4 + dy) * w * 4 + x * 4 + dx] = value
                        x++
                    }
                }
                assertEquals("row $row of $name", w, x)
            }
            val boxes = lines.drop(at + 1 + h).takeWhile { it.startsWith("box ") }.map { line ->
                line.split(' ').drop(1).map(String::toInt).let { (l, t, r, b) -> IntRect(l, t, r, b) }
            }
            Map(name, w * 4, h * 4, values, boxes)
        }
    }

    @Test
    fun findsTheReferencesBoxes() {
        assertEquals(listOf("en", "sr-cyrillic"), maps.map { it.name })
        for (map in maps) {
            val found = TextBoxes.find(map.values, map.width, map.height, 720, 1280).map { it.bounds }

            assertEquals(map.name, map.boxes.size, found.size)
            for ((expected, actual) in map.boxes.zip(found)) {
                // The reference turns a box by a few degrees when its part leans, which an axis-aligned box cannot.
                assertTrue("${map.name}: $expected and $actual", overlap(expected, actual) >= 0.8)
            }
        }
    }

    // Intersection over union.
    private fun overlap(a: IntRect, b: IntRect): Double {
        val common = a.intersect(b) ?: return 0.0
        val shared = common.width.toDouble() * common.height
        return shared / (a.width.toDouble() * a.height + b.width.toDouble() * b.height - shared)
    }

    @Test
    fun dropsThinPartsAndPartsWithALowMeanAndKeepsBoxesInsideTheImage() {
        val width = 40
        val height = 20
        val map = FloatArray(width * height)
        fun fill(left: Int, top: Int, right: Int, bottom: Int, value: Float) {
            for (y in top..bottom) for (x in left..right) map[y * width + x] = value
        }
        fill(0, 0, 9, 9, 0.9f) // kept, and its grown box is cut at the image's edge
        fill(15, 0, 30, 1, 0.9f) // two rows: thinner than 3
        fill(15, 8, 30, 16, 0.5f) // above the threshold, below the box threshold

        val boxes = TextBoxes.find(map, width, height, width, height)

        assertEquals(1, boxes.size)
        assertEquals(IntRect(0, 0, 12, 12), boxes.single().bounds)
        assertEquals(0.9, boxes.single().score, 0.0001)
    }
}
