package dev.kestrel.build

import org.gradle.api.Plugin
import org.gradle.api.Project

/** For `:testapp`, the fixture screens of the instrumented tests. It is never shipped, so it is not audited. */
class AndroidTestAppConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.applyKestrelApplicationDefaults()
    }
}
