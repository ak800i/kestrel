package dev.kestrel.eval.task

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
        phone.shown.add("Delete")
        bench.adb.hook("SCREEN_TAP") { extras ->
            if (extras["label"] == "Delete") phone.alarms.clear()
            "tapped=true count=1"
        }
        var works = true
        bench.adb.hook("VOICE") { extras ->
            if (works && extras["text"] == "Kestrel, set an alarm for 6:45 tomorrow morning") phone.alarmAt(6, 45)
            "sent"
        }

        assertEquals(Reason.OK, bench.run(ClockTasks.ALARM).reason)
        assertTrue(phone.alarms.isEmpty())
        works = false
        assertEquals(Reason.CHECK, bench.run(ClockTasks.ALARM).reason)
        bench.assertPutBack()
    }

    @Test
    fun timerPassesWithARunningTimerAndLeavesNone() {
        val bench = TaskBench()
        val phone = ClockPhone(bench.run)
        phone.shown.add("Delete")
        bench.adb.hook("SCREEN_TAP") {
            phone.alarms.clear()
            phone.ongoing = false
            "tapped=true count=1"
        }
        var works = true
        bench.adb.hook("VOICE") { extras ->
            if (works && extras["text"] == "Kestrel, start a 5 minute timer") {
                phone.timerIn(295)
                phone.ongoing = true
            }
            "sent"
        }

        assertEquals(Reason.OK, bench.run(ClockTasks.TIMER).reason)
        assertTrue(phone.alarms.isEmpty())
        works = false
        assertEquals(Reason.CHECK, bench.run(ClockTasks.TIMER).reason)
        bench.assertPutBack()
    }

    @Test
    fun eachRehearsalSetsItsItemThroughAndroidsIntentAndTheRestoreDeletesIt() {
        val bench = TaskBench()
        val phone = ClockPhone(bench.run)
        phone.shown.add("Delete")
        bench.adb.on("^am start -W -a android.intent.action.SET_ALARM --ei android.intent.extra.alarm.HOUR (\\d+) --ei android.intent.extra.alarm.MINUTES (\\d+) ") {
            phone.alarmAt(it.groupValues[1].toInt(), it.groupValues[2].toInt())
            ""
        }
        bench.adb.on("^am start -W -a android.intent.action.SET_TIMER --ei android.intent.extra.alarm.LENGTH 300 ") {
            phone.timerIn(299)
            phone.ongoing = true
            ""
        }
        bench.adb.hook("SCREEN_TAP") {
            phone.alarms.clear()
            phone.ongoing = false
            "tapped=true count=1"
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
