package dev.kestrel.build

import com.android.build.api.dsl.ApplicationExtension
import dev.kestrel.build.audit.AuditPlugin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** For `:app`. It also applies the privacy audit. */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.applyKestrelApplicationDefaults()
        target.pluginManager.apply(AuditPlugin::class.java)
    }
}

/** Shared by `:app` and `:testapp`. Only `:app` is audited, because `:testapp` is never shipped. */
internal fun Project.applyKestrelApplicationDefaults() {
    pluginManager.apply("com.android.application")
    extensions.configure<ApplicationExtension> {
        applyKestrelAndroidDefaults()
        defaultConfig {
            targetSdk { version = release(KestrelSdk.TARGET) }
        }
    }
}
