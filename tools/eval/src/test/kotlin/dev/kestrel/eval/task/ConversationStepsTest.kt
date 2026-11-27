package dev.kestrel.eval.task

import dev.kestrel.eval.hook.HookAnswer
import dev.kestrel.eval.run.SentCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The steps and checks of the multi-turn tasks (design 15.3 of sub-project 7). */
class ConversationStepsTest {
    private val run = FakeRun()
    private val scope get() = run.scope

    @Test
    fun theEndOfTheQuietTimeClosesTheConversationAndTheOwnersTouchGoesThroughGoalControl() {
        var open = true
        run.adb.hook("CONVERSATION_STATE") { "open=${if (open) "yes" else "no"} continued=no commands=1 goal=none quiet=running quietLeft=20 replyChannel=voice testMode=on" }
        run.adb.hook("CONVERSATION_CONTROL") {
            open = false
            "expire=sent"
        }
        run.adb.hook("GOAL_CONTROL") { extras -> "${extras["do"]}=sent" }

        EndQuietTime.run(scope)
        OwnerTouch.run(scope)

        assertFalse(open)
        assertEquals(listOf(mapOf("do" to "touch")), run.adb.hookCalls("GOAL_CONTROL"))
        assertTrue(run.adb.commands.none { it.startsWith("input ") })
        assertEquals("the end of the quiet time", EndQuietTime.toString())
    }

    @Test
    fun theSmallestLimitsPutBackTheLimitsThatTheyFoundAndALostAnswerLeavesWhatToDoByHand() {
        var stored = "500/60/1000"
        run.adb.hook("CONVERSATION_CONTROL") { extras ->
            val was = stored
            stored = extras["limits"] ?: "50/5/10"
            "limits=$stored was=$was"
        }

        assertEquals(SetupResult.Ready, SmallestLimits.apply(scope))
        assertEquals("50/5/10", stored)
        assertTrue(run.restores.runDownTo(0).isEmpty())
        assertEquals("500/60/1000", stored)

        run.adb.loseNext("CONVERSATION_CONTROL")
        assertThrows(Exception::class.java) { SmallestLimits.apply(scope) }
        val failures = run.restores.runDownTo(0)
        assertEquals("Kestrel > Settings > Conversations: set a round's limits back to the owner's.", failures.single().byHand)
    }

    @Test
    fun theConversationsChecksReadItsHooks() {
        scope.tag = "t1"
        run.adb.hook("CONVERSATION_STATE") { "open=yes continued=yes commands=2 goal=interrupted quiet=off quietLeft=none replyChannel=voice testMode=on" }
        run.adb.hook("AGENT_STATE") { "phase=paused-at-limit asking=none work=idle handOver=none confirming=none question=none checklist=1/2 conversation=open" }
        run.adb.hook("CONVERSATION_CONTROL") { "restored=goal-held" }
        fun passes(check: Check) = check.passes(scope, HookAnswer.EMPTY)

        assertTrue(passes(ConversationIs(open = true, continued = true, commands = 2, goal = "interrupted")))
        assertTrue(passes(ConversationIs(commands = 2)))
        assertFalse(passes(ConversationIs(open = false)))
        assertFalse(passes(ConversationIs(commands = 1)))
        assertTrue(passes(PhaseIs("paused-at-limit")))
        assertFalse(passes(PhaseIs("interrupted")))
        assertTrue(passes(ChecklistIs(1, 2)))
        assertFalse(passes(ChecklistIs(2, 2)))
        assertTrue(passes(RestoredAs("goal-held")))
        assertFalse(passes(RestoredAs("open")))
        assertEquals(mapOf("do" to "restore-check"), run.adb.hookCalls("CONVERSATION_CONTROL").last())
        scope.tag = null
        assertFalse(passes(PhaseIs("paused-at-limit")))
    }

    @Test
    fun askedInOrderFindsItsEntriesInTheirOrderAmongTheRunsAnswers() {
        // Two commands, each with its own record: the second's entries follow the first's.
        val first = SentCommand("t1", null)
        first.record.saw("confirm/typing_screen_text")
        first.record.answered("confirm")
        first.record.saw("limit")
        first.record.answered("yes")
        val second = SentCommand("t2", null)
        second.record.saw("confirm/sending")
        second.record.answered("confirm")
        second.record.saw("limit")
        second.record.keepOpen()
        scope.commands += listOf(first, second)
        fun passes(vararg entries: String) = AskedInOrder(entries.toList()).passes(scope, HookAnswer.EMPTY)

        assertTrue(passes("confirm/typing_screen_text:confirm", "confirm/sending:confirm"))
        assertTrue(passes("limit:yes", "limit:none"))
        assertFalse(passes("confirm/sending:confirm", "confirm/typing_screen_text:confirm"))
        assertFalse(passes("limit:yes", "limit:yes"))
    }

    @Test
    fun deleteTestSmsDeletesOnlyTheRunsSms() {
        val inbox = mutableMapOf(3L to "Kestrel test: the code word is maple heron", 4L to "Lunch at noon")
        run.adb.on("^content query --uri content://sms/inbox --projection _id --where ") {
            inbox.filterValues { "maple heron" in it }.keys.joinToString("\n") { "Row: 0 _id=$it" }.ifEmpty { "No result found." }
        }
        run.adb.on("^content delete --uri content://sms --where '_id=(\\d+)'$") {
            inbox.remove(it.groupValues[1].toLong())
            ""
        }
        run.adb.on("^appops get com\\.android\\.shell WRITE_SMS$") { "WRITE_SMS: allow; time=+1h ago" }

        DeleteTestSms.run(scope)

        assertEquals(setOf(4L), inbox.keys)
        assertTrue(run.adb.commands.contains("content query --uri content://sms/inbox --projection _id --where 'body LIKE '\\''%maple heron%'\\'''"))
    }

    @Test
    fun onlyAlarmAtPassesForOneAlarmClockOfTheClockAtItsTime() {
        val phone = ClockPhone(run)
        scope.notes[ClockApp.NOTE] = ClockApp.GOOGLE
        fun passes() = OnlyAlarmAt(7, 30).passes(scope, HookAnswer.EMPTY)

        assertFalse(passes())
        phone.alarmAt(7, 30)
        assertTrue(passes())
        phone.alarmAt(7, 0)
        assertFalse(passes())
    }
}
