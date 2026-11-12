package dev.kestrel.build.audit

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.util.zip.ZipFile

/** Fails when compiled project code uses network APIs outside the exceptions in `audit/rules.txt` (design spec, section 13.2). */
abstract class ScanNetworkApisTask : DefaultTask() {
    /** Android modules: the classes that AGP compiled for the variant. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classDirectories: ListProperty<Directory>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val classJars: ListProperty<RegularFile>

    /** Plain Kotlin modules: the class folders of the main source set. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val extraClassDirectories: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val rulesFile: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    init {
        classDirectories.convention(emptyList())
        classJars.convention(emptyList())
    }

    @TaskAction
    fun scan() {
        val reportFile = report.get().asFile
        reportFile.parentFile.mkdirs()
        val rules = AuditRules.parse(rulesFile.get().asFile.readText())
        val scanner = NetworkApiScanner()
        val findings = mutableListOf<NetworkFinding>()
        val directories = classDirectories.get().map { it.asFile } + extraClassDirectories.files
        directories.filter { it.isDirectory }.forEach { directory ->
            directory.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".class") }
                .forEach { findings += scanner.scan(it.readBytes()) }
        }
        classJars.get().map { it.asFile }.filter { it.isFile }.forEach { jar ->
            ZipFile(jar).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".class") }
                    .forEach { entry -> findings += scanner.scan(zip.getInputStream(entry).use { it.readBytes() }) }
            }
        }
        val (excepted, violations) = findings.distinct().partition { rules.isNetworkClassExcepted(it.className) }
        reportFile.writeText(
            buildString {
                appendLine(if (violations.isEmpty()) "No network API use outside the exceptions." else "Violations:")
                violations.forEach { appendLine(it.toString()) }
                if (excepted.isNotEmpty()) {
                    appendLine()
                    appendLine("Allowed by audit/rules.txt:")
                    excepted.forEach { appendLine(it.toString()) }
                }
            },
        )
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Network APIs are used outside the network gateway (report: $reportFile):\n" + violations.joinToString("\n"),
            )
        }
    }
}
