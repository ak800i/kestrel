package dev.kestrel.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SherpaOnnxTest {
    // The audit pins sherpa-onnx 1.13.8 because it links ONNX Runtime 1.28.2, which has no Android telemetry
    // (design spec, section 13.2, B13, B14). A new version needs a new audit, and then a new expected value here.
    @Test
    fun theLibraryIsTheAuditedOne() {
        assertEquals("1.13.8", SherpaOnnx.version)
        assertEquals("1.28.2", SherpaOnnx.onnxRuntimeVersion)
    }
}
