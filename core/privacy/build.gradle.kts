plugins {
    id("kestrel.android.library")
}

android {
    namespace = "dev.kestrel.privacy"
}

dependencies {
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
