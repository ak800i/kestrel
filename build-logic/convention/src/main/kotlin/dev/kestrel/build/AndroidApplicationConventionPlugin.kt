package dev.kestrel.build

import com.android.build.api.dsl.ApplicationExtension
import dev.kestrel.build.audit.AuditPlugin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** For `:app`. It also applies the privacy audit, and it signs the release build when the environment names a key. */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.applyKestrelApplicationDefaults()
        target.applyReleaseSigning()
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

/**
 * Signs `:app`'s release build with the key that the variables of [ReleaseSigning] name (design spec, section 17): the
 * release workflow sets them from the repository's secrets, and `scripts\install.ps1 -Release` sets them to this
 * computer's debug key. Without them, as in CI's ordinary jobs, the release build stays unsigned.
 */
private fun Project.applyReleaseSigning() {
    val signing = ReleaseSigning.from { providers.environmentVariable(it).orNull } ?: return
    extensions.configure<ApplicationExtension> {
        val key = signingConfigs.maybeCreate("release")
        key.storeFile = signing.keystore
        key.storePassword = signing.storePassword
        key.keyAlias = signing.keyAlias
        key.keyPassword = signing.keyPassword
        buildTypes.getByName("release").signingConfig = key
    }
}
