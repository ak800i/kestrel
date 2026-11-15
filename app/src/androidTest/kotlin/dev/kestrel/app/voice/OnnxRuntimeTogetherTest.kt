package dev.kestrel.app.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.app.voice.VoiceTests.graph
import dev.kestrel.app.voice.VoiceTests.silence
import dev.kestrel.voice.SherpaOnnx
import dev.kestrel.voice.models.Engine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.FloatBuffer

/**
 * Spike S8 (design spec, section 19): sherpa-onnx links its own ONNX Runtime 1.28.2 statically, and Kestrel's OCR will
 * use onnxruntime-android. Both must work in one process.
 */
@RunWith(AndroidJUnit4::class)
class OnnxRuntimeTogetherTest {
    @Before
    fun needsTheModels() = VoiceTests.assumeModels(Engine.VAD)

    @Test
    fun bothOnnxRuntimesWorkInOneProcess() = runBlocking {
        graph.voice.speechDetector().use { detector ->
            detector.accept(silence(0.1f))

            // onnxruntime-android runs Silero VAD's model on silence.
            val environment = OrtEnvironment.getEnvironment()
            environment.createSession(graph.models.file("vad-silero/silero_vad.onnx").path, OrtSession.SessionOptions()).use { session ->
                val inputs = session.inputInfo.mapValues { (_, node) ->
                    val shape = (node.info as TensorInfo).shape.map { if (it < 0) 1L else it }.toLongArray()
                    OnnxTensor.createTensor(environment, FloatBuffer.wrap(FloatArray(shape.fold(1L, Long::times).toInt())), shape)
                }
                session.run(inputs).use { result -> assertEquals(session.outputNames.size, result.size()) }
                inputs.values.forEach { it.close() }
            }

            // sherpa-onnx still works after the other ONNX Runtime ran.
            assertEquals(0, (detector.accept(silence(0.5f)) + detector.finish()).size)
        }
        assertEquals("1.28.2", SherpaOnnx.onnxRuntimeVersion)
    }
}
