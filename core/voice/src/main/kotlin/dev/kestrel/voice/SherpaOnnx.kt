package dev.kestrel.voice

import com.k2fsa.sherpa.onnx.VersionInfo

/** The versions of sherpa-onnx and of the ONNX Runtime that it links statically (design spec, sections 8.2 and 13.2). */
object SherpaOnnx {
    val version: String get() = VersionInfo.version

    val onnxRuntimeVersion: String get() = VersionInfo.onnxruntimeVersion
}
