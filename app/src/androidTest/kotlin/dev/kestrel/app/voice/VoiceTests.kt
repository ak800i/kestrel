package dev.kestrel.app.voice

import android.os.Debug
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.AppGraph
import dev.kestrel.app.KestrelApplication
import dev.kestrel.voice.SAMPLE_RATE
import dev.kestrel.voice.Synthesizer
import dev.kestrel.voice.VoiceOptions
import dev.kestrel.voice.models.Engine
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

/**
 * What the voice tests share: the app's own engines, and synthetic speech in Kestrel's voice, which stands in for the
 * owner's voice until plan 2C adds recordings (design spec, section 16.4). The tests skip themselves when the models are
 * missing; scripts\voice-test.ps1 copies the models first and fails on a skipped test.
 */
internal object VoiceTests {
    val graph: AppGraph
        get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KestrelApplication).graph

    /** The microphone delivers 20 ms frames (design spec, section 8.1). */
    const val FRAME = SAMPLE_RATE / 50

    // Kept open for the whole test run, because loading the voice takes more than a second.
    private var synthesizer: Synthesizer? = null
    private val spoken = mutableMapOf<String, FloatArray>()

    /** Skips the test unless every model of [engines] is on the device and intact. */
    fun assumeModels(vararg engines: Engine) {
        val notReady = runBlocking { graph.models.check() }.filter { it.model.engine in engines && !it.ready }
        assumeTrue("Models missing or damaged: ${notReady.map { it.model.id }}. Run scripts\\voice-test.ps1.", notReady.isEmpty())
    }

    /** [text] in Kestrel's voice without variation, so that it is the same in every run, at 16 kHz. */
    @Synchronized
    fun voice(text: String): FloatArray = spoken.getOrPut(text) {
        val active = synthesizer ?: runBlocking { graph.voice.synthesizer(VoiceOptions(variation = 0f)) }.also { synthesizer = it }
        resample(active.synthesize(text), active.sampleRate, SAMPLE_RATE)
    }

    /** [voice] with 0.5 s of silence before it and 1 s after it, as the microphone hears an utterance. */
    fun speech(text: String): FloatArray = silence(0.5f) + voice(text) + silence(1f)

    fun silence(seconds: Float) = FloatArray((seconds * SAMPLE_RATE).toInt())

    /** Feeds [samples] to [feed] in 20 ms frames, as the microphone delivers them, and collects what it returns. */
    fun <T> frames(samples: FloatArray, feed: (FloatArray) -> List<T>): List<T> =
        (samples.indices step FRAME).flatMap { start -> feed(samples.copyOfRange(start, minOf(start + FRAME, samples.size))) }

    /** Logs a measurement; scripts\voice-test.ps1 prints these lines after the tests. Never log what was said. */
    fun log(message: String) {
        Log.i("KestrelVoiceTest", message)
    }

    /** The memory that the app's process uses (PSS), in MB, as the design spec's memory target counts it (section 5.3). */
    fun pssMegabytes(): Long = Debug.getPss() / 1024

    // Linear interpolation is enough for test speech; Kestrel's microphone records at 16 kHz itself.
    private fun resample(samples: FloatArray, from: Int, to: Int): FloatArray {
        val count = (samples.size.toLong() * to / from).toInt()
        return FloatArray(count) { index ->
            val position = index.toDouble() * from / to
            val before = position.toInt()
            val after = minOf(before + 1, samples.size - 1)
            val fraction = (position - before).toFloat()
            samples[before] + (samples[after] - samples[before]) * fraction
        }
    }
}
