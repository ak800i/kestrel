package dev.kestrel.build

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** For `:app`. */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.applyKestrelApplicationDefaults()
    }
}

/** Shared by `:app` and, from plan 1B, `:testapp`. */
internal fun Project.applyKestrelApplicationDefaults() {
    pluginManager.apply("com.android.application")
    extensions.configure<ApplicationExtension> {
        applyKestrelAndroidDefaults()
        defaultConfig {
            targetSdk { version = release(KestrelSdk.TARGET) }
        }
    }
}
