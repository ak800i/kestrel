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
 * the worktree has changes outside `tools/eval/`, `docs/` and Markdown files, else 0, as "<commit> <0|1>". The debug
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
        val dirty = git("status", "--porcelain")?.let { status -> if (dirty(status)) "1" else "0" } ?: "1"
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
        private val NEUTRAL = listOf("tools/eval/", "docs/")

        /** Whether `git status --porcelain` lists a path outside the tool, the documentation and Markdown files. */
        fun dirty(status: String): Boolean = status.lines().filter { it.length > 3 }.flatMap { it.substring(3).split(" -> ") }
            .map { it.trim().removeSurrounding("\"") }
            .any { path -> NEUTRAL.none { path.startsWith(it) } && !path.endsWith(".md") }
    }
}
