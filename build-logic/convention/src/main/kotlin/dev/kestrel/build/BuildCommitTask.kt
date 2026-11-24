package dev.kestrel.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * Writes `build/commit.txt` into a debug build's assets: the commit of the worktree that the build comes from and 1 when
 * the worktree has changes outside the paths that the repository's `.gitattributes` marks `kestrel-neutral` (the task
 * suite's tool, its baseline, the documentation and Markdown files), else 0, as "<commit> <0|1>". The debug
 * hook `state` answers it, so that `:tools:eval`'s `--record-baseline` records only a build of HEAD (review I2 of plan
 * 6B). It runs at every build, because git's state is no input that Gradle sees; the assets change only when it does.
 */
abstract class BuildCommitTask : DefaultTask() {
    @get:Internal
    abstract val repository: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val exec: ExecOperations

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun write() {
        val commit = git("rev-parse", "HEAD")?.trim()?.takeIf { COMMIT.matches(it) } ?: "unknown"
        val dirty = if (dirty(git(*KESTREL_STATUS.toTypedArray()))) "1" else "0"
        val folder = outputDirectory.get().asFile.resolve("build")
        folder.mkdirs()
        folder.resolve("commit.txt").writeText("$commit $dirty\n")
    }

    private fun git(vararg args: String): String? {
        val out = ByteArrayOutputStream()
        val result = exec.exec {
            commandLine(listOf("git", "-C", repository.get().asFile.path) + args)
            standardOutput = out
            errorOutput = ByteArrayOutputStream()
            isIgnoreExitValue = true
        }
        return if (result.exitValue == 0) out.toString(Charsets.UTF_8) else null
    }

    companion object {
        private val COMMIT = Regex("[0-9a-f]{40}")

        /**
         * git's status of the paths that can change a run of Kestrel: every path but those that `.gitattributes` marks
         * `kestrel-neutral`, their one definition, which `:tools:eval` reads through the same pathspec. Each untracked file
         * is listed on its own, because a new folder's line would not name the attributes of the files in it.
         */
        val KESTREL_STATUS = listOf("status", "--porcelain", "--untracked-files=all", "--", ".", ":(exclude,attr:kestrel-neutral)")

        /** Whether [status], git's answer to [KESTREL_STATUS], lists a path; null, when git failed, counts as dirty. */
        fun dirty(status: String?): Boolean = status == null || status.isNotBlank()
    }
}
