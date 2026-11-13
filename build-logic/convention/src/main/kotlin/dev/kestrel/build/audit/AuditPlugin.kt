package dev.kestrel.build.audit

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import com.android.build.api.variant.ScopedArtifacts
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.RegularFile
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.language.base.plugins.LifecycleBasePlugin

/**
 * Registers `kestrelAudit` and the checks it runs (design spec, section 13.2), for every build variant,
 * and makes `check` run it. Exceptions live in `audit/rules.txt`, each with a written justification.
 * The audit also checks that release builds contain no test hooks (section 16.1).
 */
class AuditPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val rules = project.rootProject.layout.projectDirectory.file("audit/rules.txt")
        val audit = project.tasks.register("kestrelAudit") {
            group = "verification"
            description = "Runs Kestrel's privacy audit for this module."
        }
        project.plugins.withType(LifecycleBasePlugin::class.java) {
            project.tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) { dependsOn(audit) }
        }
        project.pluginManager.withPlugin("com.android.application") {
            configureAndroid(project, rules, audit, isApplication = true)
        }
        project.pluginManager.withPlugin("com.android.library") {
            configureAndroid(project, rules, audit, isApplication = false)
        }
        project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            val classes = project.extensions.getByType(SourceSetContainer::class.java).getByName("main").output.classesDirs
            val scan = project.tasks.register("kestrelScanNetworkApis", ScanNetworkApisTask::class.java) {
                extraClassDirectories.from(classes)
                rulesFile.set(rules)
                report.set(project.layout.buildDirectory.file("reports/kestrel-audit/network-apis.txt"))
            }
            audit.configure { dependsOn(scan) }
        }
    }

    private fun configureAndroid(project: Project, rules: RegularFile, audit: TaskProvider<Task>, isApplication: Boolean) {
        val components = project.extensions.getByType(AndroidComponentsExtension::class.java)
        // Debug builds are what the scripts install, so they are audited too.
        components.onVariants(components.selector().all()) { variant ->
            val suffix = variant.name.replaceFirstChar { it.uppercase() }
            val reports = project.layout.buildDirectory.dir("reports/kestrel-audit")
            val scan = project.tasks.register("kestrelScanNetworkApis$suffix", ScanNetworkApisTask::class.java) {
                rulesFile.set(rules)
                report.set(reports.map { it.file("network-apis-${variant.name}.txt") })
            }
            variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT)
                .use(scan)
                .toGet(ScopedArtifact.CLASSES, ScanNetworkApisTask::classJars, ScanNetworkApisTask::classDirectories)
            audit.configure { dependsOn(scan) }
            if (isApplication) {
                val dependencies = project.tasks.register("kestrelCheckDependencies$suffix", CheckDependenciesTask::class.java) {
                    rootComponent.set(variant.runtimeConfiguration.incoming.resolutionResult.rootComponent)
                    rulesFile.set(rules)
                    report.set(reports.map { it.file("dependencies-${variant.name}.txt") })
                }
                val native = project.tasks.register("kestrelScanNativeLibs$suffix", ScanNativeLibsTask::class.java) {
                    apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
                    builtArtifactsLoader.set(variant.artifacts.getBuiltArtifactsLoader())
                    rulesFile.set(rules)
                    report.set(reports.map { it.file("native-libs-${variant.name}.txt") })
                }
                audit.configure { dependsOn(dependencies, native) }
                if (variant.buildType == "release") {
                    val manifestCheck = project.tasks.register("kestrelCheckManifest$suffix", CheckManifestTask::class.java) {
                        manifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                        applicationId.set((variant as ApplicationVariant).applicationId)
                        report.set(reports.map { it.file("manifest-${variant.name}.txt") })
                    }
                    audit.configure { dependsOn(manifestCheck) }
                }
            }
        }
    }
}
