plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}

// gradle/gradle-daemon-jvm.properties names the Java version that Gradle starts its daemon on. This build has no
// toolchain download repository and downloads no JDKs, so the file lists no download addresses.
tasks.named<org.gradle.buildconfiguration.tasks.UpdateDaemonJvm>("updateDaemonJvm") {
    toolchainPlatforms.empty()
}
