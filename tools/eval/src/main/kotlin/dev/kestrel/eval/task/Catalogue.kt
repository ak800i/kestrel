package dev.kestrel.eval.task

import dev.kestrel.eval.device.DeviceKind

/** Every task of :tools:eval (design D4 and D7 of sub-project 6), in the suite's order. */
object Catalogue {
    val ALL: List<Task> = D3Tasks.ALL + StandIns.ALL + InjectionFixtures.ALL

    fun find(name: String): Task? = ALL.firstOrNull { it.name == name }

    /**
     * The tasks of a suite on [kind]: live, the D3 tasks and plan 6B's suite tasks that the device runs; without a model,
     * the stand-ins and the suite tasks that have a script, which drive the device with the scripted model.
     */
    fun suite(kind: DeviceKind, live: Boolean): List<Task> = ALL.filter { task ->
        val runs = if (live) task.kind == TaskKind.D3 || task.kind == TaskKind.SUITE else task.kind == TaskKind.STAND_IN || (task.kind == TaskKind.SUITE && task.dryRun != null)
        kind in task.devices && runs
    }
}
