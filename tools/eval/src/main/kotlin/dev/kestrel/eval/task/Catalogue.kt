package dev.kestrel.eval.task

import dev.kestrel.eval.device.DeviceKind

/** Every task of :tools:eval (design D4 and D7 of sub-project 6), in the suite's order. */
object Catalogue {
    val ALL: List<Task> =
        SettingsTasks.ALL + ClockTasks.ALL + PeopleTasks.ALL + MessageTasks.ALL + AppTasks.ALL + D3Tasks.ALL + StandIns.ALL + InjectionFixtures.ALL + ConversationTasks.ALL

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

    /**
     * The multi-turn tasks on [kind] (design 15.3 of sub-project 7), which run apart from the suite, with a baseline part
     * of their own (the main spec's 16.5): live, those that the device runs with a model ([Task.runsLive]); without a
     * model, on kestrel-api37 only, those whose own script runs them, so a task that runs as its stand-in there runs once,
     * as its stand-in.
     */
    fun multiTurn(kind: DeviceKind, live: Boolean): List<Task> = ALL.filter { task ->
        val runs = if (live) task.runsLive else kind == DeviceKind.EMULATOR && task.dryRun != null && task.dryRun.standIn == null
        task.kind == TaskKind.CONVERSATION && kind in task.devices && runs
    }
}
