plugins {
    id("kestrel.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
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
    implementation(project(":core:privacy"))
    implementation(project(":core:safety"))
    implementation(project(":core:screen"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.startup.runtime)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // Compose's test library brings Espresso 3.5, which fails on Android 17: it looks for InputManager.getInstance().
    androidTestImplementation(libs.androidx.test.espresso.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.okhttp.mockwebserver3)
}

// The instrumented tests drive the fixture screens of :testapp, so it is installed first. They also turn on
// Kestrel's accessibility service, so they run after the other modules' device tests instead of alongside them.
tasks.matching { it.name == "connectedDebugAndroidTest" }.configureEach {
    dependsOn(":testapp:installDebug")
    mustRunAfter(":core:privacy:connectedDebugAndroidTest")
}
