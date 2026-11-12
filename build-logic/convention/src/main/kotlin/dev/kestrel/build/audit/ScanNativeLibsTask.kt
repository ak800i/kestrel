package dev.kestrel.build.audit

import com.android.build.api.variant.BuiltArtifactsLoader
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.zip.ZipFile

/** Fails when a native library in the APK imports networking functions (design spec, section 13.2). */
abstract class ScanNativeLibsTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apkDirectory: DirectoryProperty

    @get:Internal
    abstract val builtArtifactsLoader: Property<BuiltArtifactsLoader>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val rulesFile: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun scan() {
        val artifacts = builtArtifactsLoader.get().load(apkDirectory.get())
            ?: throw GradleException("No APK found in ${apkDirectory.get().asFile}")
        val rules = AuditRules.parse(rulesFile.get().asFile.readText())
        val scanned = mutableListOf<String>()
        val violations = mutableListOf<String>()
        artifacts.elements.forEach { element ->
            ZipFile(File(element.outputFile)).use { zip ->
                zip.entries().asSequence()
                    .filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
                    .forEach { entry ->
                        scanned += entry.name
                        val library = entry.name.substringAfterLast('/')
                        val imports = try {
                            ElfReader.importedSymbols(zip.getInputStream(entry).use { it.readBytes() })
                        } catch (e: IllegalArgumentException) {
                            throw GradleException("Cannot read the imports of ${entry.name}: ${e.message}", e)
                        }
                        imports.filter { it in NativeLibraryRules.NETWORK_SYMBOLS }
                            .filterNot { rules.isNativeSymbolExcepted(library, it) }
                            .forEach { violations += "${entry.name} imports $it" }
                    }
            }
        }
        val reportFile = report.get().asFile
        reportFile.parentFile.mkdirs()
        reportFile.writeText(
            "Scanned ${scanned.size} native libraries.\n" + scanned.joinToString("\n", postfix = "\n") +
                if (violations.isEmpty()) "No networking imports found.\n" else violations.joinToString("\n", postfix = "\n"),
        )
        if (violations.isNotEmpty()) {
            throw GradleException("Native libraries import networking functions (report: $reportFile):\n" + violations.joinToString("\n"))
        }
    }
}
