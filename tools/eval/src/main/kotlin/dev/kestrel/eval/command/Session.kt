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
import dev.kestrel.eval.run.ConversationGuard
import dev.kestrel.eval.run.Installer
import dev.kestrel.eval.run.LineWriter
import dev.kestrel.eval.run.LiveGate
import dev.kestrel.eval.run.LiveSession
import dev.kestrel.eval.run.Preflight
import dev.kestrel.eval.run.PrintConsole
import dev.kestrel.eval.run.Restores
import dev.kestrel.eval.run.RunOptions
import dev.kestrel.eval.run.Runner
import dev.kestrel.eval.run.ScriptCheck
import dev.kestrel.eval.run.ScriptInstaller
import dev.kestrel.eval.task.Task
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

    // The series' guard of Kestrel's conversation: its runs tell it their commands, and the preflight's cleanup reads them.
    val conversations = ConversationGuard(hooks, environment.clock)
    val preflight = Preflight(device, hooks, restores, environment.clock, Installer(environment.root, environment.processes), conversations)

    /**
     * Before the first run: a live session left on goes off without `--live`, the preflight, the check of [scripts], the
     * scripts that the runs without a model install ([ScriptCheck]), and with `--live` the live gate.
     */
    fun prepare(tasks: List<Task>, live: Boolean, scripts: List<String>) {
        if (!live) LiveSession(hooks, console, device.serial).ensureOff()
        preflight.prepare(needsTestApp = tasks.any { it.needsTestApp })
        ScriptCheck(ScriptInstaller(device, hooks, work), device.serial).check(scripts)
        if (live) LiveGate(hooks, restores, device.serial).open()
    }

    fun runner(options: RunOptions, writer: LineWriter): Runner =
        Runner(device, hooks, restores, environment.clock, console, config, work, writer, options, preflight::waitListening, conversations)

    /**
     * Runs [body], then the restores, then on kestrel-api37 the check of its accounts and profile. When the body threw, a
     * failure of the check is added to the body's error as suppressed, so that the first error is kept and both reach
     * the console; when the body succeeded, the check's failure is the command's error.
     */
    fun <T> guarded(body: () -> T): T {
        preflight.wake()
        val accounts = if (device.kind == DeviceKind.EMULATOR) AccountGuard(device, hooks) else null
        val before = accounts?.read()
        var failure: Throwable? = null
        try {
            return body()
        } catch (e: Throwable) {
            failure = e
            throw e
        } finally {
            restores.report(restores.runAll())
            if (accounts != null && before != null) {
                try {
                    accounts.check(before)
                } catch (e: Exception) {
                    if (failure == null) throw e
                    failure.addSuppressed(e)
                }
            }
        }
    }
}
