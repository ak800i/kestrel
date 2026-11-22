plugins {
    id("kestrel.android.library")
}

android {
    namespace = "dev.kestrel.datatools"
}

// Android's data APIs and intents for the agent's data tools (design D1 and D12 of sub-project 5); plan 5B fills it.
dependencies {
    implementation(project(":core:agent"))
    implementation(project(":core:commands"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
