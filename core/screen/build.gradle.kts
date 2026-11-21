plugins {
    id("kestrel.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.kestrel.screen"
}

dependencies {
    // The snapshot model, plain Kotlin, so that :core:agent and :core:safety can read it (design D1 of sub-project 5).
    api(project(":core:screen-model"))
    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
