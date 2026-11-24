package dev.kestrel.eval.task

import dev.kestrel.eval.config.LocalConfig
import dev.kestrel.eval.hook.HookAnswer
import dev.kestrel.eval.run.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The calendar and contacts tasks against a scripted device that passes and one that fails, with every restore run (design D4, D8 and D9 of sub-project 6). */
class PeopleTasksTest {
    private val config = LocalConfig.of(mapOf("emulatorGmailAddress" to "pera.second@example.com"))

    private fun bench(): Pair<TaskBench, FakeProviders> {
        val bench = TaskBench(config = config)
        bench.adb.granted["dev.kestrel"] = mutableSetOf("android.permission.READ_CONTACTS")
        bench.adb.hook("AGENT_ANSWER") { "answered" }
        return bench to FakeProviders(bench.adb)
    }

    @Test
    fun calendarAddAndCalendarReadLeaveNoTestEvent() {
        val (bench, providers) = bench()
        var works = true
        bench.adb.hook("VOICE") { extras ->
            if (works && extras["text"] == "Kestrel, add maple heron to my calendar tomorrow at 3 pm") providers.events.add(FakeProviders.Event(99, 1, "Maple heron", providers.at(24, 15)))
            "sent"
        }

        assertEquals(Reason.OK, bench.run(PeopleTasks.CALENDAR_ADD).reason)
        works = false
        assertEquals(Reason.CHECK, bench.run(PeopleTasks.CALENDAR_ADD).reason)
        assertEquals(Reason.OK, bench.run(PeopleTasks.CALENDAR_READ).reason)
        assertEquals("Kestrel test maple heron", providers.events.last().title)
        bench.replyMarker = "0"
        assertEquals(Reason.CHECK, bench.run(PeopleTasks.CALENDAR_READ).reason)
        assertTrue(providers.events.all { it.deleted })
        // list_events would ask for the permission, a hand-over that no script answers; it stays granted, in the kept set.
        assertTrue(bench.adb.commands.contains("pm grant dev.kestrel android.permission.READ_CALENDAR"))
        assertTrue(bench.adb.commands.none { it.startsWith("pm revoke") })
        bench.assertPutBack()
    }

    @Test
    fun contactAddAndContactNumberLeaveNoTestContactAndKeepTheGrantedPermission() {
        val (bench, providers) = bench()
        var works = true
        bench.adb.hook("VOICE") { extras ->
            if (works && extras["text"] == "Kestrel, add a contact maple heron with the number +1 202 555 0160") {
                providers.raws.add(FakeProviders.Raw(providers.raws.size + 1L, "Maple Heron", "+12025550160"))
            }
            "sent"
        }

        assertEquals(Reason.OK, bench.run(PeopleTasks.CONTACT_ADD).reason)
        works = false
        assertEquals(Reason.CHECK, bench.run(PeopleTasks.CONTACT_ADD).reason)
        assertTrue(providers.live().isEmpty())
        assertTrue(bench.adb.commands.contains("pm grant dev.kestrel android.permission.WRITE_CONTACTS"))
        assertTrue(bench.adb.commands.none { it.startsWith("pm revoke") })

        var digits = "1"
        bench.adb.hook("AGENT_TASK") { extras -> if (extras["digits"] != null) "state=done digits=$digits" else bench.end }
        assertEquals(Reason.OK, bench.run(PeopleTasks.CONTACT_NUMBER).reason)
        assertEquals("2025550143", bench.adb.hookCalls("AGENT_TASK").first { it["digits"] != null }["digits"])
        digits = "0"
        assertEquals(Reason.CHECK, bench.run(PeopleTasks.CONTACT_NUMBER).reason)
        assertTrue(providers.live().isEmpty())
        bench.assertPutBack()
    }

    @Test
    fun aGrantThatKestrelRefusesFailsOnlyTheRunAndTakesNothingBack() {
        val (bench, providers) = bench()
        // A Kestrel that does not request WRITE_CONTACTS: pm grant and pm revoke both end with a SecurityException.
        bench.adb.on("^pm (grant|revoke) dev\\.kestrel android\\.permission\\.WRITE_CONTACTS$") { null }

        assertEquals(Reason.SETUP, bench.run(PeopleTasks.CONTACT_ADD).reason)
        assertTrue(bench.adb.commands.none { it.startsWith("pm revoke") })
        assertTrue(providers.live().isEmpty())
        bench.assertPutBack()
    }

    @Test
    fun contactDeleteDeclinedPassesWhenKestrelAskedAndTheContactStayedAndEachRehearsalPasses() {
        val (bench, providers) = bench()
        val questions = ArrayDeque(listOf("confirming=deleting"))
        bench.adb.hook("AGENT_STATE") { "phase=running asking=confirm work=running-task handOver=none ${questions.removeFirstOrNull() ?: "confirming=none"}" }
        var polls = 0
        bench.adb.hook("AGENT_TASK") { if (++polls < 4) "state=running" else bench.end }

        assertEquals(Reason.OK, bench.run(PeopleTasks.CONTACT_DELETE_DECLINED).reason)
        assertEquals("no", bench.adb.hookCalls("AGENT_ANSWER").single()["answer"])
        assertTrue(providers.live().isEmpty())
        questions.add("confirming=deleting")
        polls = 0
        bench.adb.hook("VOICE") {
            providers.live().forEach { it.deleted = true }
            "sent"
        }
        assertEquals(Reason.CHECK, bench.run(PeopleTasks.CONTACT_DELETE_DECLINED).reason)
        bench.assertPutBack()

        for (task in PeopleTasks.ALL) {
            val steps = (task.rehearsal as? Rehearsal.Steps)?.steps ?: continue
            val scope = bench.run.scope
            for (step in task.setup + steps) assertEquals(task.name, SetupResult.Ready, step.apply(scope))
            assertTrue(task.name, task.check.all { it.passes(scope, HookAnswer.EMPTY) })
            assertTrue(task.name, bench.run.restores.runAll().isEmpty())
            assertTrue(task.name, providers.live().isEmpty() && providers.events.all { it.deleted })
        }
    }
}
