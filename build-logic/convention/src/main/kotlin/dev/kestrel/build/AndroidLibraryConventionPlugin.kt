package dev.kestrel.build

import com.android.build.api.dsl.LibraryExtension
import dev.kestrel.build.audit.AuditPlugin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** For the Android modules under `core/`. It also applies the privacy audit. */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            extensions.configure<LibraryExtension> { applyKestrelAndroidDefaults() }
            pluginManager.apply(AuditPlugin::class.java)
        }
    }
}
