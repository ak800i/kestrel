package dev.kestrel.eval.command

import dev.kestrel.eval.device.DeviceGuard
import dev.kestrel.eval.task.Catalogue
import dev.kestrel.eval.task.Rehearsal
import java.io.PrintStream

/**
 * `list [--device <s>]`: every task with its kind, its devices, its form without a model and its rehearsal; with a
 * device, only the tasks that it runs.
 */
class ListCommand(private val environment: Environment, private val command: Command.ListTasks) {
    fun execute(out: PrintStream): Int {
        val kind = command.device?.let { DeviceGuard(environment.clock).accept(environment.adb(it)).kind }
        for (task in Catalogue.ALL.filter { kind == null || kind in it.devices }) {
            val dryRun = task.dryRun?.let { dry -> dry.script + (dry.standIn?.let { " on $it" } ?: "") } ?: "none"
            val rehearsal = if (task.rehearsal is Rehearsal.Steps) "steps" else "setup-only"
            out.println(
                "task=${task.name} kind=${task.kind.name.lowercase().replace('_', '-')} devices=${task.devices.sorted().joinToString(",") { it.folder }} " +
                    "dryRun=${dryRun.replace(' ', '_')} rehearsal=$rehearsal",
            )
        }
        return 0
    }
}
