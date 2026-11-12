package dev.kestrel.build.audit

import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.RegularFile
import org.gradle.api.tasks.TaskProvider

/**
 * Registers `kestrelAudit` and the checks it runs (design spec, section 13.2). Exceptions live in
 * `audit/rules.txt`, each with a written justification.
 */
class AuditPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val rules = project.rootProject.layout.projectDirectory.file("audit/rules.txt")
        val audit = project.tasks.register("kestrelAudit") {
            group = "verification"
            description = "Runs Kestrel's privacy audit for this module."
        }
        project.pluginManager.withPlugin("com.android.application") {
            configureAndroid(project, rules, audit, isApplication = true)
        }
        project.pluginManager.withPlugin("com.android.library") {
            configureAndroid(project, rules, audit, isApplication = false)
        }
    }

    private fun configureAndroid(project: Project, rules: RegularFile, audit: TaskProvider<Task>, isApplication: Boolean) {
        val components = project.extensions.getByType(AndroidComponentsExtension::class.java)
        components.onVariants(components.selector().withBuildType("release")) { variant ->
            val suffix = variant.name.replaceFirstChar { it.uppercase() }
            val reports = project.layout.buildDirectory.dir("reports/kestrel-audit")
            if (isApplication) {
                val dependencies = project.tasks.register("kestrelCheckDependencies$suffix", CheckDependenciesTask::class.java) {
                    rootComponent.set(variant.runtimeConfiguration.incoming.resolutionResult.rootComponent)
                    rulesFile.set(rules)
                    report.set(reports.map { it.file("dependencies-${variant.name}.txt") })
                }
                audit.configure { dependsOn(dependencies) }
            }
        }
    }
}
