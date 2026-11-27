package dev.kestrel.eval.task

import dev.kestrel.eval.device.DeviceKind
import dev.kestrel.eval.hook.HookAnswer
import dev.kestrel.eval.run.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** alarm and timer against a scripted device that passes and one that fails, with the test's alarm and timer deleted (design D8 and D9 of sub-project 6). */
class ClockTasksTest {
    @Test
    fun alarmPassesWithTheNewAlarmAndLeavesNoTestAlarm() {
        val bench = TaskBench()
        val phone = ClockPhone(bench.run)
        var works = true
        bench.adb.hook("VOICE") { extras ->
            if (works && extras["text"] == "Kestrel, set an alarm for 6:45 tomorrow morning") phone.setAlarm(6, 45)
            "sent"
        }

        assertEquals(Reason.OK, bench.run(ClockTasks.ALARM).reason)
        assertTrue(phone.alarms.isEmpty())
        assertEquals(listOf(ClockPhone.CLEAR), bench.adb.commands.filter { it.startsWith("pm ") })
        works = false
        assertEquals(Reason.CHECK, bench.run(ClockTasks.ALARM).reason)
        bench.assertPutBack()
    }

    @Test
    fun alarmIsUntestableOnTheA71BeforeAnyChangeOrRestore() {
        val bench = TaskBench(DeviceKind.A71)
        val phone = ClockPhone(bench.run, ClockApp.SAMSUNG)
        phone.alarmAt(6, 45)
        bench.adb.hook("VOICE") {
            phone.setAlarm(6, 45)
            "sent"
        }

        assertEquals(Reason.UNTESTABLE, bench.run(ClockTasks.ALARM).reason)
        assertTrue(bench.run.console.lines.toString(), bench.run.console.lines.any { it.endsWith(": the owner's real alarms are on this phone.") })
        assertTrue(bench.adb.commands.toString(), bench.adb.commands.none { it.startsWith("input ") || it.startsWith("am ") || it.startsWith("pm ") || it.startsWith("dumpsys alarm") || it.startsWith("cmd package") })
        assertTrue(bench.adb.hookCalls("SCREEN_HAS").isEmpty() && bench.adb.hookCalls("SCREEN_TAP").isEmpty() && bench.adb.hookCalls("VOICE").isEmpty())
        assertEquals(1, phone.clocks())
        bench.assertPutBack()

        val run = FakeRun(DeviceKind.A71)
        ClockPhone(run, ClockApp.SAMSUNG)
        assertEquals(SetupResult.Untestable("the owner's real alarms are on this phone"), ClockFree.apply(run.scope))
        assertTrue(run.adb.commands.isEmpty())
        assertEquals(0, run.restores.size)
    }

    @Test
    fun timerPassesWithARunningTimerAndLeavesNone() {
        val bench = TaskBench()
        val phone = ClockPhone(bench.run)
        var works = true
        bench.adb.hook("VOICE") { extras ->
            if (works && extras["text"] == "Kestrel, start a 5 minute timer") {
                phone.timerIn(295)
                phone.localOnly = true
            }
            "sent"
        }

        assertEquals(Reason.OK, bench.run(ClockTasks.TIMER).reason)
        assertTrue(phone.alarms.isEmpty())
        assertEquals(listOf(ClockPhone.CLEAR), bench.adb.commands.filter { it.startsWith("pm ") })
        works = false
        assertEquals(Reason.CHECK, bench.run(ClockTasks.TIMER).reason)
        bench.assertPutBack()
    }

    @Test
    fun eachRehearsalSetsItsItemThroughAndroidsIntentAndTheRestoreDeletesIt() {
        val bench = TaskBench()
        val phone = ClockPhone(bench.run)
        bench.adb.on("^am start -W -a android.intent.action.SET_ALARM --ei android.intent.extra.alarm.HOUR (\\d+) --ei android.intent.extra.alarm.MINUTES (\\d+) ") {
            phone.setAlarm(it.groupValues[1].toInt(), it.groupValues[2].toInt())
            ""
        }
        bench.adb.on("^am start -W -a android.intent.action.SET_TIMER --ei android.intent.extra.alarm.LENGTH 300 ") {
            phone.timerIn(299)
            phone.localOnly = true
            ""
        }

        for (task in ClockTasks.ALL) {
            val scope = bench.run.scope
            for (step in task.setup + (task.rehearsal as Rehearsal.Steps).steps) assertEquals(task.name, SetupResult.Ready, step.apply(scope))
            assertTrue(task.name, task.check.all { it.passes(scope, HookAnswer.EMPTY) })
            assertTrue(task.name, bench.run.restores.runAll().isEmpty())
            assertTrue(task.name, phone.alarms.isEmpty())
        }
    }
}
