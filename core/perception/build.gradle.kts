plugins {
    id("kestrel.android.library")
}

android {
    namespace = "dev.kestrel.perception"
}

// On-device OCR with PP-OCRv5 through ONNX Runtime (design D20 of sub-project 5). The standalone ONNX Runtime 1.28.0
// works beside the copy that sherpa-onnx links (spike S8); the audit pins 1.28.* (B14).
dependencies {
    implementation(project(":core:screen-model"))
    implementation(libs.onnxruntime.android)
    testImplementation(libs.junit)
}