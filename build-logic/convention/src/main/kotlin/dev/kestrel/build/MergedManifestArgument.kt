package dev.kestrel.build

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider

/**
 * Names an app's merged manifest to a unit-test task in the system property [PROPERTY], and makes the task wait for the
 * manifest and run again when it changes. Add it to the task's `jvmArgumentProviders`, with [manifest] set from
 * `variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)`. Design 12.2 of sub-project 7: a unit test checks the
 * conversation screen's filters in the manifest that Android gets.
 */
abstract class MergedManifestArgument : CommandLineArgumentProvider {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    override fun asArguments(): Iterable<String> = listOf("-D$PROPERTY=${manifest.get().asFile.absolutePath}")

    companion object {
        const val PROPERTY = "kestrel.mergedManifest"
    }
}
