package dev.kestrel.eval.device

import kotlin.time.Duration

/** What adb answered: the exit code that it passed on, and its output and error output, read as UTF-8. */
data class ShellResult(val exitCode: Int, val out: String, val err: String)

/** adb could not start, or did not end within its time, so its answer is lost. */
class AdbException(message: String) : Exception(message)

/**
 * adb for one serial (design D3 of sub-project 6). [shell] runs `adb -s <serial> shell <command>` and [host] runs
 * `adb -s <serial> <args>`, such as `emu avd name` or `push`. Both throw [AdbException] when adb did not answer in time.
 * Only [Device] and [DeviceGuard] call it, so that every command on a device goes through the guard.
 */
interface Adb {
    val serial: String

    fun shell(command: String, timeout: Duration): ShellResult

    fun host(args: List<String>, timeout: Duration): ShellResult
}
