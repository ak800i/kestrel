import dev.kestrel.build.CopyAssetsTask

plugins {
    id("kestrel.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.kestrel.voice"
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

androidComponents {
    onVariants { variant ->
        // The models' manifest, whose hashes Kestrel checks the model files against (design spec, section 8.10).
        val manifest = tasks.register<CopyAssetsTask>("${variant.name}ModelManifestAsset") {
            files.from(rootProject.layout.projectDirectory.file("models/manifest.json"))
            folder.set("models")
        }
        variant.sources.assets?.addGeneratedSourceDirectory(manifest, CopyAssetsTask::outputDirectory)
    }
}

// ModelManifestTest reads the repository's manifest.
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.layout.projectDirectory.file("models/manifest.json"))
        .withPathSensitivity(PathSensitivity.NONE)
        .withPropertyName("modelManifest")
}

dependencies {
    // sherpa-onnx's Android library with ONNX Runtime linked statically (design spec, section 8.2). It comes from the
    // GitHub release that settings.gradle.kts names, and gradle/verification-metadata.xml checks its hash. The release has
    // no metadata, so the dependency asks for the AAR.
    implementation(libs.sherpa.onnx) { artifact { type = "aar" } }
    api(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
