package dev.kestrel.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

/**
 * Copies files from the repository into one folder of an app's assets. Register it with
 * `variant.sources.assets?.addGeneratedSourceDirectory(task, CopyAssetsTask::outputDirectory)`. The About page's licence
 * texts and the models' manifest reach the app this way.
 */
abstract class CopyAssetsTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val files: ConfigurableFileCollection

    /** The folder in the assets, such as `licences`. */
    @get:Input
    abstract val folder: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val fileSystem: FileSystemOperations

    @TaskAction
    fun copy() {
        // Syncing the whole output folder removes everything else from it, such as the files of an earlier `folder`.
        fileSystem.sync {
            into(outputDirectory)
            into(folder.get()) { from(files) }
        }
    }
}
