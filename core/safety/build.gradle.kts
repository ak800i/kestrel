plugins {
    id("kestrel.jvm.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    // The snapshot model and the matcher's folding and phone rules (design D1 of sub-project 5).
    api(project(":core:screen-model"))
    implementation(project(":core:commands"))
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
