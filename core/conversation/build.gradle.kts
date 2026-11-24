plugins {
    id("kestrel.jvm.library")
    id("org.jetbrains.kotlin.plugin.serialization")
    `java-test-fixtures`
}

// The conversation with the owner (design 5.1 and 5.2 of sub-project 7): its data, its state machine and engine, the goal
// runner, the records and their context block, the channels' port, the store with its search and retention, and Kestrel's
// own requests. Plain Kotlin, so that the whole conversation runs in JVM tests with the agent's scripted model; it never
// depends on :app, :core:voice, :core:screen or :core:privacy. Its test fixtures (the fake channel, the in-memory archive,
// the manual clock and the fake goal environment) use only Kotlin, coroutines and the agent's fixtures, so that plan 7D's
// JVM tests can use them too.
dependencies {
    api(project(":core:agent"))
    api(project(":core:safety"))
    implementation(project(":core:commands"))
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    testFixturesApi(testFixtures(project(":core:agent")))
    testFixturesApi(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
