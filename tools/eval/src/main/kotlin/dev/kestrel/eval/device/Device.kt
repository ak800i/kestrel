package dev.kestrel.eval.device

import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** A command on the device that failed three times, with the device's last complaint. */
class DeviceCommandFailed(message: String) : Exception(message)

/** The serial no longer names the device that [DeviceGuard] accepted, so nothing more runs on it. */
class DeviceChanged(message: String) : Exception(message)

/**
 * The device that [DeviceGuard] accepted (design D3 of sub-project 6). Every shell command runs inside the check of
 * `Format-TestDeviceCommand`, in the same `adb shell` call: `case $(getprop <property>) in <pattern>) <command> && echo
 * && echo kestrel-end;; *) echo kestrel-refused;; esac`, so that another device that took the serial is never changed,
 * and the marker tells an empty answer from a failed command. Each step chooses how its command runs:
 * - [read] and [idempotent] try up to 3 times, 2 s apart, as `Invoke-SettingsCommand` does, and are only for commands
 *   that do the same when they run twice;
 * - [once] runs a command that creates something or acts once, and answers null when its answer was lost; the step then
 *   reads the state back instead of sending the command again.
 *
 * A device bounded by a wait's [Deadline] ([until]) gives each adb call at most the time left, starts no retry whose pause
 * would end past it, and sends nothing once it has passed, so that the wait ends at its deadline however slow adb is.
 */
class Device(
    private val adb: Adb,
    val test: TestDevice,
    private val clock: Clock = SystemClock,
    private val deadline: Deadline? = null,
) {
    val serial: String get() = test.serial
    val kind: DeviceKind get() = test.kind

    /** This device for a wait that ends at [deadline]. */
    fun until(deadline: Deadline): Device = Device(adb, test, clock, deadline)

    private val property = if (test.kind == DeviceKind.EMULATOR) "ro.boot.qemu.avd_name" else "ro.product.model"
    private val pattern = if (test.kind == DeviceKind.EMULATOR) DeviceGuard.EMULATOR_AVD else "${DeviceGuard.A71_MODEL}*"

    /**
     * A command that only reads, such as `getprop`, `settings get`, `dumpsys` or `content query`. [lost] names the reason
     * when an answer that adb gave is still a lost one, as a call of the debug hooks that failed on the device, and the read
     * is then tried again like one whose answer adb lost.
     */
    fun read(command: String, timeout: Duration = TIMEOUT, lost: (List<String>) -> String? = { null }): List<String> = repeated(command, timeout, lost)

    /**
     * A command that does the same when it runs twice: `settings put`, `cmd uimode night`, `cmd notification set_dnd`,
     * `svc`, `am force-stop`, `pm grant`, `pm revoke`, `appops set` and `rm -f`.
     */
    fun idempotent(command: String, timeout: Duration = TIMEOUT): List<String> = repeated(command, timeout) { null }

    /** A command that runs once; null when its answer was lost, also when it failed on the device. */
    fun once(command: String, timeout: Duration = TIMEOUT): List<String>? = when (val answer = attempt(command, timeout)) {
        is Answer.Done -> answer.lines
        is Answer.Lost -> null
    }

    /** A command of the emulator's console, right after the check that the console still belongs to kestrel-api37. */
    fun emu(args: List<String>, timeout: Duration = TIMEOUT): String {
        if (kind != DeviceKind.EMULATOR) throw DeviceChanged("Only kestrel-api37 has an emulator console; $serial is ${kind.label}.")
        val what = "emu ${args.joinToString(" ")}"
        if (DeviceGuard.consoleName(adb, hostTime(DeviceGuard.QUICK, what)) != DeviceGuard.EMULATOR_AVD) throw DeviceChanged(changed(what))
        return adb.host(listOf("emu") + args, hostTime(timeout, what)).out
    }

    /** Copies a file of this computer to the device, after the guard's check; a copy overwrites, so it may run twice. */
    fun push(local: Path, remote: String) {
        read("true")
        transfer(listOf("push", local.toString(), remote))
    }

    /** The output of `adb exec-out <args>` after the guard's check, for a read that is no shell's, such as `uiautomator dump /dev/tty`. */
    fun execOut(args: List<String>, timeout: Duration = TIMEOUT): String {
        read("true")
        return adb.host(listOf("exec-out") + args, hostTime(timeout, "exec-out")).out
    }

    /** Installs [apk] with `adb install -r` after the guard's check; a second install of the same file does the same. */
    fun install(apk: Path) {
        read("true")
        val result = adb.host(listOf("install", "-r", apk.toString()), hostTime(PUSH_TIMEOUT, "install"))
        if (result.exitCode != 0) throw DeviceCommandFailed("adb install failed on $serial: ${complaint(result)}")
    }

    /** Copies [remote] of the device to [local] with `adb pull`, after the guard's check; a copy overwrites, so it may run twice. */
    fun pull(remote: String, local: Path) {
        read("true")
        Files.createDirectories(local.toAbsolutePath().parent)
        transfer(listOf("pull", remote, local.toString()))
    }

    /** The command inside the guard's check, as `Format-TestDeviceCommand` writes it. */
    fun guarded(command: String): String = "case \$(getprop $property) in $pattern) $command && echo && echo $END;; *) echo $REFUSED;; esac"

    /** A copy by `adb push` or `adb pull`, which overwrites, so it may run up to 3 times, 2 s apart. */
    private fun transfer(args: List<String>) {
        var reason = "no answer"
        for (attempt in 0 until TRIES) {
            if (attempt > 0 && !pause()) break
            val time = time(PUSH_TIMEOUT)
            if (time == null) {
                reason = OUT_OF_TIME
                break
            }
            val result = try {
                adb.host(args, time)
            } catch (e: AdbException) {
                reason = e.message.orEmpty()
                null
            }
            if (result != null && result.exitCode == 0) return
            if (result != null) reason = complaint(result)
        }
        throw DeviceCommandFailed("adb ${args.first()} failed on $serial: $reason")
    }

    private fun repeated(command: String, timeout: Duration, lost: (List<String>) -> String?): List<String> {
        var reason = "no answer"
        for (attempt in 0 until TRIES) {
            if (attempt > 0 && !pause()) break
            when (val answer = attempt(command, timeout)) {
                is Answer.Done -> reason = lost(answer.lines) ?: return answer.lines
                is Answer.Lost -> reason = answer.reason
            }
        }
        throw DeviceCommandFailed("Could not run '$command' on $serial: $reason")
    }

    // The pause before a retry; false, without a pause, when the retry would start at or after the wait's deadline.
    private fun pause(): Boolean {
        if (deadline != null && deadline.left <= RETRY) return false
        clock.sleep(RETRY)
        return true
    }

    // The time that an adb call may take: [timeout], or the time left before the wait's deadline when that is shorter;
    // null when the deadline has passed.
    private fun time(timeout: Duration): Duration? {
        val left = deadline?.left ?: return timeout
        return if (left.isPositive()) minOf(timeout, left) else null
    }

    private fun hostTime(timeout: Duration, what: String): Duration =
        time(timeout) ?: throw DeviceCommandFailed("Did not run 'adb $what' on $serial: $OUT_OF_TIME")

    private fun attempt(command: String, timeout: Duration): Answer {
        val time = time(timeout) ?: return Answer.Lost(OUT_OF_TIME)
        val result = try {
            adb.shell(guarded(command), time)
        } catch (e: AdbException) {
            return Answer.Lost(e.message.orEmpty())
        }
        val lines = result.out.replace("\r\n", "\n").split('\n').let { if (it.lastOrNull() == "") it.dropLast(1) else it }
        when (lines.lastOrNull()?.trim()) {
            END -> {
                var count = lines.size - 1
                // The line break before the marker leaves an empty line after an answer that ends with one.
                if (count > 0 && lines[count - 1] == "") count--
                return Answer.Done(lines.take(count))
            }
            REFUSED -> throw DeviceChanged(changed(command))
        }
        return Answer.Lost(complaint(result))
    }

    private fun changed(command: String) = "Refusing to run '$command' on $serial: it is no longer the test device that :tools:eval accepted."

    private fun complaint(result: ShellResult): String =
        (result.err + "\n" + result.out).lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "exit code ${result.exitCode}" }

    private sealed interface Answer {
        data class Done(val lines: List<String>) : Answer

        data class Lost(val reason: String) : Answer
    }

    companion object {
        const val END = "kestrel-end"
        const val REFUSED = "kestrel-refused"
        private const val OUT_OF_TIME = "the wait's time ran out"
        val TIMEOUT = 60.seconds
        private val PUSH_TIMEOUT = 2.minutes
        private val RETRY = 2.seconds
        private const val TRIES = 3
    }
}
