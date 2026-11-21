plugins {
    id("kestrel.android.library")
}

android {
    namespace = "dev.kestrel.perception"
}

// On-device OCR with PP-OCRv5 through ONNX Runtime (design D20 of sub-project 5); plan 5C fills it.
dependencies {
    implementation(project(":core:screen-model"))
}
