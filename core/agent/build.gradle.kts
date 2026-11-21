plugins {
    id("kestrel.jvm.library")
    id("org.jetbrains.kotlin.plugin.serialization")
    `java-test-fixtures`
}

// The agent's loop, observation, tools, memory and routines (design D1 of sub-project 5). Its test fixtures hold the
// scripted model and the fakes, which :app's debug build uses too, so they use only Kotlin, coroutines and
// kotlinx.serialization: the audit checks the debug runtime classpath.
dependencies {
    api(project(":core:screen-model"))
    api(project(":core:safety"))
    api(project(":core:providers"))
    implementation(project(":core:commands"))
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    testFixturesApi(libs.kotlinx.coroutines.core)
    testFixturesApi(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
