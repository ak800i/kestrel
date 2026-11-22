package dev.kestrel.app.agent.eyes

import android.graphics.BitmapFactory
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.perception.Detector
import dev.kestrel.perception.OcrEngine
import dev.kestrel.perception.OcrFiles
import dev.kestrel.perception.OcrImage
import dev.kestrel.screen.model.IntRect
import dev.kestrel.voice.models.Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Kestrel's OCR on the device (design D20 and D32 item 31 of sub-project 5): the three models load and run with ONNX
 * Runtime 1.28.0 (r48 could not confirm its HardSwish and HardSigmoid kernels), Kestrel reads the five fixture screens of
 * `scripts\draw-ocr-fixtures.ps1` as RapidOCR's Python reference did (`assets/ocr/golden.txt`), and the time and memory
 * are measured for the record. The models come from Kestrel's model folder, where `scripts\push-models.ps1` puts them;
 * without them the tests are skipped. The fixtures hold only test text, and no assertion message holds any text.
 */
@RunWith(AndroidJUnit4::class)
class OcrDeviceTest {
    @Before
    fun needsTheModels() = VoiceTests.assumeModels(Engine.OCR)

    private val files: OcrFiles get() = runBlocking { withContext(Dispatchers.IO) { OcrFiles.under(VoiceTests.graph.models.root) } }

    private fun fixture(name: String): OcrImage {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val bitmap = assets.open("ocr/$name.png").use { BitmapFactory.decodeStream(it) }
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return OcrImage(bitmap.width, bitmap.height, pixels)
        } finally {
            bitmap.recycle()
        }
    }

    private fun whole(image: OcrImage) = listOf(IntRect(0, 0, image.width, image.height))

    @Test
    fun theThreeModelsLoadAndRun() {
        OcrEngine.open(files).use { engine ->
            val image = fixture("sr-cyrillic")
            val boxes = engine.detect(image)
            assertTrue(boxes.isNotEmpty())
            // With Cyrillic nearby, both recognisers read the box.
            val line = engine.recognise(image, boxes.first(), cyrillicNearby = true)
            assertTrue(line != null && line.text.isNotEmpty())
        }
    }

    @Test
    fun readsTheFiveFixturesAsTheReferenceDid() {
        val golden = InstrumentationRegistry.getInstrumentation().context.assets.open("ocr/golden.txt")
            .bufferedReader().readLines().filter { it.isNotBlank() }.map { it.split(' ', limit = 6) }
        OcrEngine.open(files).use { engine ->
            for (name in FIXTURES) {
                val image = fixture(name)
                val read = engine.read(image, whole(image), cyrillicNearby = name == "sr-cyrillic")
                val expected = golden.filter { it[0] == name }
                assertEquals("lines of $name", expected.size, read.size)
                expected.forEachIndexed { index, line ->
                    val box = IntRect(line[1].toInt(), line[2].toInt(), line[3].toInt(), line[4].toInt())
                    // The same text, in a box that overlaps the reference's by at least 70 % (intersection over union).
                    val match = read.any { it.text == line[5] && overlap(it.bounds, box) >= 0.7 }
                    assertTrue("line $index of $name", match)
                }
            }
        }
    }

    @Test
    fun measuresTimeAndMemory() {
        // A full window of a 1080 × 2400 phone and a region, as look_closer reads them.
        val full = fixture("en").scaled(1080, 1920)
        val region = fixture("small").crop(IntRect(0, 80, 360, 200))
        for (threads in listOf(2, 4)) {
            val started = SystemClock.elapsedRealtime()
            OcrEngine.open(files, threads).use { engine ->
                val loadMs = SystemClock.elapsedRealtime() - started
                for (limit in listOf(Detector.LIMIT, 1280)) {
                    for ((kind, image) in listOf("full" to full, "region" to region)) {
                        val detectStart = SystemClock.elapsedRealtime()
                        val boxes = engine.detect(image, limit)
                        val detectMs = SystemClock.elapsedRealtime() - detectStart
                        val recogniseStart = SystemClock.elapsedRealtime()
                        val lines = boxes.mapNotNull { engine.recognise(image, it) }
                        val recogniseMs = SystemClock.elapsedRealtime() - recogniseStart
                        assertTrue("lines of $kind", lines.isNotEmpty())
                        Log.i(TAG, "ocr image=$kind limit=$limit threads=$threads loadMs=$loadMs detectMs=$detectMs recogniseMs=$recogniseMs lines=${lines.size} pssKb=${Debug.getPss()}")
                    }
                }
            }
        }
    }

    private fun overlap(a: IntRect, b: IntRect): Double {
        val common = a.intersect(b) ?: return 0.0
        val shared = common.width.toDouble() * common.height
        return shared / (a.width.toDouble() * a.height + b.width.toDouble() * b.height - shared)
    }

    private companion object {
        const val TAG = "KestrelOcr"
        val FIXTURES = listOf("en", "sr-latin", "sr-cyrillic", "small", "dark")
    }
}
