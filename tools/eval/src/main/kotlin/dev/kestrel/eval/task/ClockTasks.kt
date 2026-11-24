package dev.kestrel.eval.task

import dev.kestrel.eval.device.DeviceKind

/**
 * The suite's clock tasks (design D8 of sub-project 6): an alarm at a time that the clock has no alarm at, which runs
 * only on kestrel-api37 and is untestable on the A71, where the owner's real alarms are ([NoOwnerAlarms]), and a
 * 5-minute timer on both devices, each deleted after the run by the clock's own taps and checked in `dumpsys alarm`. A
 * rehearsal sets its item through Android's intent, without the clock's screen.
 */
object ClockTasks {
    private val BOTH = setOf(DeviceKind.EMULATOR, DeviceKind.A71)

    val ALARM = Task(
        name = "alarm",
        kind = TaskKind.SUITE,
        request = Request("set an alarm for {time} tomorrow morning"),
        devices = BOTH,
        answers = Answers.NONE,
        verdict = VerdictRule.Finished,
        setup = listOf(NoOwnerAlarms, HomeScreen, ClockFree()),
        check = listOf(NextAlarmAt),
        dryRun = DryRun("alarm.json"),
        rehearsal = Rehearsal.Steps(listOf(AlarmIntent)),
    )

    val TIMER = Task(
        name = "timer",
        kind = TaskKind.SUITE,
        request = Request("start a 5 minute timer"),
        devices = BOTH,
        answers = Answers.NONE,
        verdict = VerdictRule.Finished,
        setup = listOf(HomeScreen, TimerCleanup),
        check = listOf(TimerRunning),
        dryRun = DryRun("timer.json"),
        rehearsal = Rehearsal.Steps(listOf(TimerIntent)),
    )

    val ALL = listOf(ALARM, TIMER)
}
