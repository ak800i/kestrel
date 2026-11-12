package dev.kestrel.build.audit

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Fails when a runtime dependency is denied, has the wrong pinned version or is not reviewed (design spec, section 13.2). */
abstract class CheckDependenciesTask : DefaultTask() {
    @get:Input
    abstract val rootComponent: Property<ResolvedComponentResult>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val rulesFile: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val dependencies = linkedSetOf<Coordinates>()
        val seen = mutableSetOf<String>()
        fun visit(component: ResolvedComponentResult) {
            if (!seen.add(component.id.displayName)) return
            val id = component.id
            if (id is ModuleComponentIdentifier) dependencies += Coordinates(id.group, id.module, id.version)
            component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { visit(it.selected) }
        }
        visit(rootComponent.get())
        val problems = AuditRules.parse(rulesFile.get().asFile.readText()).checkDependencies(dependencies)
        val reportFile = report.get().asFile
        reportFile.parentFile.mkdirs()
        reportFile.writeText(
            dependencies.joinToString("\n", postfix = "\n") +
                if (problems.isEmpty()) "\nAll dependencies passed.\n" else "\nProblems:\n" + problems.joinToString("\n", postfix = "\n"),
        )
        if (problems.isNotEmpty()) {
            throw GradleException("Dependency audit failed (report: $reportFile):\n" + problems.joinToString("\n"))
        }
    }
}
