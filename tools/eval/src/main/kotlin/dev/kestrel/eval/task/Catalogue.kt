package dev.kestrel.eval.task

import dev.kestrel.eval.device.DeviceKind

/** Every task of :tools:eval (design D4 and D7 of sub-project 6), in the suite's order. */
object Catalogue {
    val ALL: List<Task> = SettingsTasks.ALL + ClockTasks.ALL + PeopleTasks.ALL + MessageTasks.ALL + D3Tasks.ALL + StandIns.ALL + InjectionFixtures.ALL

    fun find(name: String): Task? = ALL.firstOrNull { it.name == name }

    /**
     * The tasks of a suite on [kind]: live, the D3 tasks and plan 6B's suite tasks that the device runs; without a model,
     * the stand-ins and, on kestrel-api37, the suite tasks, whose scripts drive the real apps there (design D7).
     */
    fun suite(kind: DeviceKind, live: Boolean): List<Task> = ALL.filter { task ->
        val scripted = task.kind == TaskKind.SUITE && task.dryRun != null && kind == DeviceKind.EMULATOR
        val runs = if (live) task.kind == TaskKind.D3 || task.kind == TaskKind.SUITE else task.kind == TaskKind.STAND_IN || scripted
        kind in task.devices && runs
    }
}
