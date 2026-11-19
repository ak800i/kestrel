package dev.kestrel.app.voice

import android.os.SystemClock
import dev.kestrel.app.listen.SPEED_SAMPLE
import dev.kestrel.app.listen.SPEED_WARM_UP
import dev.kestrel.app.voice.VoiceTests.graph
import dev.kestrel.app.voice.VoiceTests.log
import dev.kestrel.app.voice.VoiceTests.pssMegabytes
import dev.kestrel.voice.ModelsNotReadyException
import dev.kestrel.voice.models.Engine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * Kokoro's two models on the device's own processor (plan 2E; A71 session of sub-project 2, item 9): Kestrel's fp32 model
 * and sherpa-onnx's int8 model, each with 2 and with 4 threads. For each, it logs the time to load, the real-time factor
 * of the sentence of Kestrel's own measure after a short warm-up, and the process's PSS before the load, after it and
 * after the sentence. The rise during the load (after it minus before it) is what the A71 session compares: the int8
 * model loads first, so that among the test's own loads, memory that the process keeps after a closed voice can only
 * lower the fp32 model's rise, never the int8 model's; a test that ran before it, such as EchoTimingTest's Kokoro case,
 * can lower both. A model that is not on the device is logged as such. Counts and times only. It fails
 * only when no Kokoro model could be measured. It changes nothing on the device, so it runs on a phone too.
 */
class KokoroModelsTest {
    @Before
    fun needsTheModels() = VoiceTests.assumeModels(Engine.TTS, optional = setOf("tts-kokoro"))

    @Test
    fun eachKokoroModelOnTheDeviceIsMeasured() = runBlocking {
        var measured = 0
        for ((name, model) in MODELS) {
            for (threads in THREADS) {
                val before = pssMegabytes()
                val started = SystemClock.elapsedRealtime()
                val voice = try {
                    graph.voice.kokoro(model, threads)
                } catch (e: ModelsNotReadyException) {
                    log("kokoro models, $name: not on the device")
                    break
                }
                voice.use {
                    val loaded = SystemClock.elapsedRealtime()
                    val afterLoad = pssMegabytes()
                    it.synthesize(SPEED_WARM_UP)
                    val spoken = SystemClock.elapsedRealtime()
                    val samples = it.synthesize(SPEED_SAMPLE)
                    val took = SystemClock.elapsedRealtime() - spoken
                    val seconds = samples.size / it.sampleRate.toFloat()
                    log(
                        String.format(
                            Locale.ROOT,
                            "kokoro models, %s, %d threads: loaded in %d ms; %.1f s of speech in %d ms (real-time " +
                                "factor %.2f); process PSS %d MB before the load, %d MB after it, %d MB after the speech",
                            name, threads, loaded - started, seconds, took, took / 1_000f / seconds, before, afterLoad,
                            pssMegabytes(),
                        ),
                    )
                    measured++
                }
            }
        }
        assertTrue("No Kokoro model could be measured", measured > 0)
    }

    private companion object {
        // The int8 model first: among this test's loads, kept memory can then only lower fp32's rise.
        val MODELS = listOf("int8" to "tts-kokoro-int8/model.int8.onnx", "fp32" to "tts-kokoro/model.onnx")
        val THREADS = listOf(2, 4)
    }
}
