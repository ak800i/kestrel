package dev.kestrel.eval.command

import dev.kestrel.eval.device.FakeClock
import dev.kestrel.eval.device.LocalProcesses
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.time.Duration.Companion.seconds

/**
 * A git repository in a temporary folder with Kestrel's own `.gitattributes`, which marks the paths whose changes change no
 * run of Kestrel, and an [environment] whose git is the real one, so that a test sees what git answers for that definition.
 */
class TestRepository {
    val root: Path = Files.createTempDirectory("eval-git")
    val environment = Environment(root, { error("no device") }, LocalProcesses, FakeClock(), PrintStream(ByteArrayOutputStream(), true), {})

    init {
        // The test runs in tools/eval, two folders below the repository's root.
        Files.copy(Paths.get("../../.gitattributes"), root.resolve(".gitattributes"))
        git("init", "-q")
    }

    fun write(path: String, text: String) {
        val file = root.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    /** Commits every change and returns the commit. */
    fun commit(): String {
        git("add", "-A")
        git("-c", "user.name=Kestrel test", "-c", "user.email=test@example.invalid", "commit", "-q", "-m", "test")
        return git("rev-parse", "HEAD").trim()
    }

    private fun git(vararg args: String): String {
        val result = LocalProcesses.run(listOf("git", "-C", root.toString()) + args, 30.seconds)
        check(result.exitCode == 0) { "git ${args.first()} failed: ${result.err}" }
        return result.out
    }
}
