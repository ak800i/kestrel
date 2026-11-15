plugins {
    id("kestrel.android.library")
}

android {
    namespace = "dev.kestrel.voice"
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    // sherpa-onnx's Android library with ONNX Runtime linked statically (design spec, section 8.2). It comes from the
    // GitHub release that settings.gradle.kts names, and gradle/verification-metadata.xml checks its hash.
    implementation("com.k2fsa.sherpa.onnx:sherpa-onnx-static-link-onnxruntime:${libs.versions.sherpaOnnx.get()}@aar")
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
