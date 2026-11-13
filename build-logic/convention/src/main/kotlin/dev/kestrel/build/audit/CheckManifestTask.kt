package dev.kestrel.build.audit

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Fails when a release build's merged manifest declares a test hook (design spec, section 16.1). */
abstract class CheckManifestTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    @get:Input
    abstract val applicationId: Property<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val hooks = findTestHooks(manifest.get().asFile.readText(), applicationId.get())
        val reportFile = report.get().asFile
        reportFile.parentFile.mkdirs()
        reportFile.writeText(if (hooks.isEmpty()) "No test hooks.\n" else "Test hooks:\n" + hooks.joinToString("\n", postfix = "\n"))
        if (hooks.isNotEmpty()) {
            throw GradleException("A release build must not contain test hooks (report: $reportFile):\n" + hooks.joinToString("\n"))
        }
    }
}
