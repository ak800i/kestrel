package dev.kestrel.build

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion

/** Settings that every Kestrel Android module shares: SDK levels (design spec, section 6), Java 17 and packaging. */
internal fun CommonExtension.applyKestrelAndroidDefaults() {
    compileSdk { version = release(KestrelSdk.COMPILE) }
    defaultConfig.apply {
        minSdk { version = release(KestrelSdk.MIN) }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions.apply {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions.unitTests.isReturnDefaultValues = true
    packaging.resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*", "/META-INF/NOTICE*")
    // Kestrel has no native code of its own. Libraries from dependencies arrive stripped,
    // and stripping them again would need an NDK on every machine that builds Kestrel.
    packaging.jniLibs.keepDebugSymbols += "**/*.so"
}
