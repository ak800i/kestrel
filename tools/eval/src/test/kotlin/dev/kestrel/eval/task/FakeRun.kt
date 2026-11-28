package dev.kestrel.eval.task

import dev.kestrel.eval.config.LocalConfig
import dev.kestrel.eval.config.Redactions
import dev.kestrel.eval.device.Device
import dev.kestrel.eval.device.DeviceKind
import dev.kestrel.eval.device.FakeAdb
import dev.kestrel.eval.device.FakeClock
import dev.kestrel.eval.device.TestDevice
import dev.kestrel.eval.hook.HookClient
import dev.kestrel.eval.hook.Hooks
import dev.kestrel.eval.run.ConversationGuard
import dev.kestrel.eval.run.FakeConsole
import dev.kestrel.eval.run.Restores
import java.nio.file.Files

/** A run's scope on a [FakeAdb], for the tests of the steps, the checks and the tasks. */
class FakeRun(kind: DeviceKind = DeviceKind.EMULATOR, marker: String = "maple heron", config: LocalConfig = LocalConfig.EMPTY) {
    val adb = if (kind == DeviceKind.EMULATOR) FakeAdb() else FakeAdb(serial = "R58N66RVD7Z", model = "SM-A715F", avd = "", sdk = 33)
    val clock = FakeClock()
    val console = FakeConsole()
    val device = Device(adb, TestDevice(adb.serial, kind, if (kind == DeviceKind.EMULATOR) 37 else 33), clock)
    val hooks = Hooks(HookClient(device))
    val shutdownHooks = mutableListOf<Thread>()
    val restores = Restores(console, Redactions(config)) { shutdownHooks.add(it) }
    val conversations = ConversationGuard(hooks, clock)
    val scope = RunScope(device, hooks, restores, clock, console, config, Files.createTempDirectory("eval-run"), RunValues(marker), conversations)

    /** Starts the restores' shutdown hook, as Ctrl+C does, and returns it once it has begun to stop new changes. */
    fun ctrlC(): Thread {
        val hook = shutdownHooks.single().apply { start() }
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!restores.stopping && System.nanoTime() < deadline) Thread.sleep(1)
        return hook
    }
}
