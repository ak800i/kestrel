package dev.kestrel.build

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class BuildCommitTaskTest {
    /** A git repository in a temporary folder with Kestrel's own `.gitattributes`, which marks the paths that change no run of Kestrel. */
    private val repository: File = Files.createTempDirectory("build-commit").toFile().also {
        // The test runs in build-logic/convention, two folders below the repository's root.
        File("../../.gitattributes").copyTo(it.resolve(".gitattributes"))
    }

    private fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-C", repository.path) + args).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ${args.first()} failed: $out" }
        return out
    }

    private fun write(path: String, text: String) = repository.resolve(path).apply { parentFile.mkdirs() }.writeText(text)

    private fun dirty(): Boolean = BuildCommitTask.dirty(git(*BuildCommitTask.KESTREL_STATUS.toTypedArray()))

    @Test
    fun onlyAChangeOutsideThePathsThatGitattributesMarksNeutralMakesTheBuildDirty() {
        git("init", "-q")
        for (path in listOf("app/src/main/kotlin/A.kt", "eval/baseline.json", "tools/eval/A.kt", "docs/p.md", "README.md")) write(path, "1")
        git("add", "-A")
        git("-c", "user.name=Kestrel test", "-c", "user.email=test@example.invalid", "commit", "-q", "-m", "test")
        assertFalse(dirty())

        // As while one device's part of the baseline is recorded and not yet committed.
        write("eval/baseline.json", "2")
        write("tools/eval/A.kt", "2")
        write("README.md", "2")
        write("docs/superpowers/plans/new.md", "new")
        write("core/agent/NOTES.md", "new")
        assertFalse(dirty())

        write("core/agent/src/main/kotlin/New File.kt", "new")
        assertTrue(dirty())
        repository.resolve("core/agent/src/main/kotlin/New File.kt").delete()
        // A file renamed out of the tool counts by its new path.
        git("mv", "tools/eval/A.kt", "core/agent/A.kt")
        assertTrue(dirty())
        // A git that fails counts as a dirty worktree.
        assertTrue(BuildCommitTask.dirty(null))
    }
}
