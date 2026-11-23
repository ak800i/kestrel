package dev.kestrel.eval.command

import dev.kestrel.eval.config.LocalConfig
import dev.kestrel.eval.config.Redactions
import dev.kestrel.eval.device.Device
import dev.kestrel.eval.device.DeviceGuard
import dev.kestrel.eval.device.DeviceKind
import dev.kestrel.eval.hook.HookClient
import dev.kestrel.eval.hook.Hooks
import dev.kestrel.eval.run.AccountGuard
import dev.kestrel.eval.run.Console
import dev.kestrel.eval.run.Installer
import dev.kestrel.eval.run.LineWriter
import dev.kestrel.eval.run.LiveGate
import dev.kestrel.eval.run.LiveSession
import dev.kestrel.eval.run.Preflight
import dev.kestrel.eval.run.PrintConsole
import dev.kestrel.eval.run.Restores
import dev.kestrel.eval.run.RunOptions
import dev.kestrel.eval.run.Runner
import dev.kestrel.eval.task.Task
import dev.kestrel.eval.task.TaskKind
import java.nio.file.Path

/**
 * One command's device and what works on it (design D2 and D3 of sub-project 6): the local configuration and the
 * console's redactions, the restores, the device that [DeviceGuard] accepted (a refusal otherwise), its hooks, the
 * preflight and the runner. [guarded] runs a command's body between the reads of kestrel-api37's accounts and profile,
 * and puts the device back at its end, however it ends.
 */
class Session(private val environment: Environment, serial: String) {
    val config: LocalConfig = LocalConfig.read(environment.root.resolve("corpus").resolve("owner").resolve("a71-config.json"))
    val console: Console = PrintConsole(environment.out, Redactions(config))
    val restores = Restores(console, Redactions(config), environment.addShutdownHook)
    val device: Device = environment.adb(serial).let { adb -> Device(adb, DeviceGuard(environment.clock).accept(adb), environment.clock) }
    val hooks = Hooks(HookClient(device))
    val work: Path = environment.root.resolve("build").resolve("eval").resolve(serial)
    val preflight = Preflight(device, hooks, restores, environment.clock, Installer(environment.root, environment.processes))

    /** Before the first run: a live session left on goes off without `--live`, the preflight, and with `--live` the live gate. */
    fun prepare(tasks: List<Task>, live: Boolean) {
        if (!live) LiveSession(hooks, console, device.serial).ensureOff()
        preflight.prepare(needsTestApp = tasks.any { it.kind == TaskKind.STAND_IN || it.kind == TaskKind.INJECTION })
        if (live) LiveGate(hooks, restores, device.serial).open()
    }

    fun runner(options: RunOptions, writer: LineWriter): Runner =
        Runner(device, hooks, restores, environment.clock, console, config, work, writer, options, preflight::waitListening)

    fun <T> guarded(body: () -> T): T {
        preflight.wake()
        val accounts = if (device.kind == DeviceKind.EMULATOR) AccountGuard(device, hooks) else null
        val before = accounts?.read()
        try {
            return body()
        } finally {
            restores.report(restores.runAll())
            if (accounts != null && before != null) accounts.check(before)
        }
    }
}
