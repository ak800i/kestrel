package dev.kestrel.perception

import org.junit.Assert.assertEquals
import org.junit.Test

/** CTC's decoding against the recognisers' best classes per step, which the Python reference saved for five boxes. */
class CtcDecoderTest {
    private class Box(val classes: Int, val dictionary: Dictionary, val steps: List<Pair<Int, Float>>, val text: String, val score: Double)

    // Each box has three lines: "reading <model> <dictionary lines> <steps> <score> <text as hex code points>", "chars
    // <index>:<hex code point> …" for the classes that its steps use, and "steps <index>:<score> …".
    private val boxes: List<Box> = Reference.lines("reference.txt").let { lines ->
        lines.indices.filter { lines[it].startsWith("reading ") }.map { at ->
            val head = lines[at].split(' ')
            val size = head[2].toInt()
            val dictionaryLines = MutableList(size) { "\u0000" }
            for (entry in lines[at + 1].removePrefix("chars ").split(' ')) {
                val (index, code) = entry.split(':')
                dictionaryLines[index.toInt() - 1] = String(Character.toChars(code.toInt(16)))
            }
            val steps = lines[at + 2].removePrefix("steps ").split(' ').map { entry ->
                entry.split(':').let { (index, score) -> index.toInt() to score.toFloat() }
            }
            val text = head.drop(5).joinToString("") { String(Character.toChars(it.toInt(16))) }
            Box(size + 2, Dictionary(dictionaryLines), steps, text, head[4].toDouble())
        }
    }

    @Test
    fun decodesEachBoxAsTheReferenceDid() {
        assertEquals(5, boxes.size)
        for (box in boxes) {
            // The step's best class gets its saved score, every other class an equal share of the rest.
            val scores = FloatArray(box.steps.size * box.classes)
            box.steps.forEachIndexed { step, (index, score) ->
                val rest = (1 - score) / (box.classes - 1)
                for (c in 0 until box.classes) scores[step * box.classes + c] = if (c == index) score else rest
            }

            val reading = CtcDecoder.decode(scores, box.steps.size, box.dictionary)

            assertEquals(box.text, reading.text)
            assertEquals(box.score, reading.score, 0.0001)
        }
    }

    @Test
    fun aRepeatSurvivesOnlyAcrossABlankAndNothingKeptScoresZero() {
        val dictionary = Dictionary.parse("s\n")
        fun steps(vararg best: Int): FloatArray = FloatArray(best.size * 3).also { scores ->
            best.forEachIndexed { step, index -> scores[step * 3 + index] = 0.9f }
        }

        assertEquals("s", CtcDecoder.decode(steps(1, 1, 1), 3, dictionary).text)
        assertEquals("ss", CtcDecoder.decode(steps(1, 0, 1), 3, dictionary).text)
        assertEquals("s s", CtcDecoder.decode(steps(1, 2, 1), 3, dictionary).text)
        assertEquals(Reading("", 0.0), CtcDecoder.decode(steps(0, 0), 2, dictionary))
    }
}
