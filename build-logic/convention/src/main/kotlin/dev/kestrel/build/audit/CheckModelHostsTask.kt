package dev.kestrel.build.audit

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails when `models/manifest.json` lists a download host that `audit/rules.txt` does not allow, or downloads from a host
 * that its `downloadHosts` do not cover (design spec, section 13.2; release plan).
 */
abstract class CheckModelHostsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val rulesFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifestFile: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val manifest = manifestFile.get().asFile.readText()
        val problems = AuditRules.parse(rulesFile.get().asFile.readText()).checkModelHosts(manifest)
        val reportFile = report.get().asFile
        reportFile.parentFile.mkdirs()
        reportFile.writeText(
            ManifestHosts.downloadHosts(manifest).joinToString("\n", postfix = "\n") +
                if (problems.isEmpty()) "\nEvery model host is allowed.\n" else "\nProblems:\n" + problems.joinToString("\n", postfix = "\n"),
        )
        if (problems.isNotEmpty()) {
            throw GradleException("Model host audit failed (report: $reportFile):\n" + problems.joinToString("\n"))
        }
    }
}
