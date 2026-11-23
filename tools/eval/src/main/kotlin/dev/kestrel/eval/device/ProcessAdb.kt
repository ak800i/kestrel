package dev.kestrel.eval.device

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration

/** Runs a program on this computer and reads its output as UTF-8. A seam, so that the tests give their own. */
fun interface Processes {
    /** Runs [command], whose first item is the program; throws [AdbException] when it cannot start or runs past [timeout]. */
    fun run(command: List<String>, timeout: Duration): ShellResult
}

/** [Processes] through [ProcessBuilder]. A program that runs past its time is ended with its whole process tree. */
object LocalProcesses : Processes {
    private const val READER_MILLIS = 5_000L

    override fun run(command: List<String>, timeout: Duration): ShellResult {
        val program = File(command.first()).name
        val process = try {
            ProcessBuilder(command).start()
        } catch (e: IOException) {
            throw AdbException("$program could not start (${e.javaClass.simpleName}).")
        }
        process.outputStream.close()
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val readers = listOf(
            thread(isDaemon = true) { process.inputStream.use { it.copyTo(out) } },
            thread(isDaemon = true) { process.errorStream.use { it.copyTo(err) } },
        )
        if (!process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            throw AdbException("$program did not end within $timeout.")
        }
        readers.forEach { it.join(READER_MILLIS) }
        return ShellResult(process.exitValue(), out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }
}

/** [Adb] through the adb program of the Android SDK. */
class ProcessAdb(
    private val program: String,
    override val serial: String,
    private val processes: Processes = LocalProcesses,
) : Adb {
    override fun shell(command: String, timeout: Duration): ShellResult {
        // Windows hands a double quote inside an argument to adb in a form that the device's shell reads differently, so
        // every device command quotes with single quotes only, as the commands of scripts\lib\Kestrel.ps1 do.
        require('"' !in command) { "A device command may not hold a double quote." }
        return processes.run(listOf(program, "-s", serial, "shell", command), timeout)
    }

    override fun host(args: List<String>, timeout: Duration): ShellResult = processes.run(listOf(program, "-s", serial) + args, timeout)
}
