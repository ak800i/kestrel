import dev.kestrel.build.CopyAssetsTask

plugins {
    id("kestrel.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.kestrel.app"
    defaultConfig {
        applicationId = "dev.kestrel"
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core:commands"))
    implementation(project(":core:privacy"))
    implementation(project(":core:providers"))
    implementation(project(":core:safety"))
    implementation(project(":core:screen"))
    implementation(project(":core:voice"))
    // sub-project 5B: dependencies
    implementation(project(":core:datatools"))
    // sub-project 5C: dependencies
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.startup.runtime)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // Compose's test library brings Espresso 3.5, which fails on Android 17: it looks for InputManager.getInstance().
    androidTestImplementation(libs.androidx.test.espresso.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    // The Copilot stand-in of the debug hook provider-standin (design D20 of sub-project 4); release builds lack it.
    debugImplementation(libs.okhttp.mockwebserver3)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.okhttp.mockwebserver3)
    // Spike S8: Kestrel's OCR will use onnxruntime-android beside sherpa-onnx's own ONNX Runtime (design spec, section 19).
    androidTestImplementation(libs.onnxruntime.android)
    // SystemUiFilterTest builds observations with plan 5A's ObservationBuilder (design D9 of sub-project 5).
    androidTestImplementation(project(":core:agent"))
}

androidComponents {
    onVariants { variant ->
        // Kestrel's licence, the third-party notices and the licence texts in `licences`, which the About page shows
        // (design spec, section 15).
        val licences = tasks.register<CopyAssetsTask>("${variant.name}LicenceAssets") {
            val root = rootProject.layout.projectDirectory
            files.from(root.file("LICENSE"), root.file("THIRD_PARTY_NOTICES.md"), root.dir("licences").asFileTree)
            folder.set("licences")
        }
        variant.sources.assets?.addGeneratedSourceDirectory(licences, CopyAssetsTask::outputDirectory)
        // The provider catalog and its overrides, which AppGraph merges on first use (design spec, section 10.2).
        val catalog = tasks.register<CopyAssetsTask>("${variant.name}CatalogAssets") {
            val root = rootProject.layout.projectDirectory
            files.from(root.file("catalog/providers.json"), root.file("catalog/overrides.json"))
            folder.set("catalog")
        }
        variant.sources.assets?.addGeneratedSourceDirectory(catalog, CopyAssetsTask::outputDirectory)
    }
}

// The instrumented tests drive the fixture screens of :testapp, so it is installed first. They also turn on
// Kestrel's accessibility service, so they run after the other modules' device tests instead of alongside them.
tasks.matching { it.name == "connectedDebugAndroidTest" }.configureEach {
    dependsOn(":testapp:installDebug")
    mustRunAfter(":core:privacy:connectedDebugAndroidTest", ":core:voice:connectedDebugAndroidTest")
}
