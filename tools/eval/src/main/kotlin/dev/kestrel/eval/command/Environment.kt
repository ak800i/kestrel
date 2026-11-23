package dev.kestrel.eval.command

import dev.kestrel.eval.device.Adb
import dev.kestrel.eval.device.Clock
import dev.kestrel.eval.device.LocalProcesses
import dev.kestrel.eval.device.ProcessAdb
import dev.kestrel.eval.device.Processes
import dev.kestrel.eval.device.Refusal
import dev.kestrel.eval.device.SystemClock
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * What the commands take from this computer: the repository's folder (scripts\eval.ps1 starts the tool there), adb for a
 * serial, the local programs, the clock, the console's stream and the JVM's shutdown hook. The tests give their own.
 */
class Environment(
    val root: Path,
    val adb: (String) -> Adb,
    val processes: Processes,
    val clock: Clock,
    val out: PrintStream,
    val addShutdownHook: (Thread) -> Unit,
) {
    companion object {
        /** This computer: adb of the Android SDK in ANDROID_HOME, which scripts\eval.ps1 sets through Use-KestrelToolchain. */
        fun local(out: PrintStream): Environment = Environment(
            root = Paths.get("").toAbsolutePath(),
            adb = { serial -> ProcessAdb(adbProgram(), serial) },
            processes = LocalProcesses,
            clock = SystemClock,
            out = out,
            addShutdownHook = { Runtime.getRuntime().addShutdownHook(it) },
        )

        private fun adbProgram(): String {
            val home = System.getenv("ANDROID_HOME") ?: throw Refusal("ANDROID_HOME is not set; run the tool through scripts\\eval.ps1.")
            val tools = Paths.get(home, "platform-tools")
            return listOf("adb.exe", "adb").map { tools.resolve(it) }.firstOrNull { Files.isRegularFile(it) }?.toString()
                ?: throw Refusal("No adb in $tools.")
        }
    }
}
