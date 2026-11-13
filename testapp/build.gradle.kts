plugins {
    id("kestrel.android.testapp")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.kestrel.testapp"
    defaultConfig {
        applicationId = "dev.kestrel.testapp"
        versionCode = 1
        versionName = "1.0"
    }
    buildFeatures {
        compose = true
    }
    lint {
        // The fixtures show fixed English text, which tests look for, so they have no translatable resources.
        disable += setOf("SetTextI18n", "HardcodedText")
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.activity.compose)
    // Otherwise lifecycle 2.9 arrives through Compose, and its lint checks no longer run with this version of lint.
    implementation(libs.androidx.lifecycle.runtime.ktx)
}
