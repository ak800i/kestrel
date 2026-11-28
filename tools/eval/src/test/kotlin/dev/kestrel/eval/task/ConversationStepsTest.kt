package dev.kestrel.eval.task

import dev.kestrel.eval.device.Alarms
import dev.kestrel.eval.hook.HookAnswer
import dev.kestrel.eval.hook.HookError
import dev.kestrel.eval.run.SentCommand
import dev.kestrel.eval.run.Stopping
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
        // The run's own conversation, which carries the hooks' mark, so the end of the quiet time goes through expire-typed.
        run.adb.hook("CONVERSATION_STATE") {
            "open=${if (open) "yes" else "no"} continued=no commands=1 goal=none quiet=running quietLeft=20 replyChannel=voice testMode=on typed=${if (open) "yes" else "no"}"
        }
        run.adb.hook("CONVERSATION_CONTROL") {
            open = false
            "expire=sent"
        }
        run.adb.hook("GOAL_CONTROL") { extras -> "${extras["do"]}=sent" }

        EndQuietTime.run(scope)
        OwnerTouch.run(scope)

        assertFalse(open)
        assertEquals(listOf(mapOf("do" to "expire-typed")), run.adb.hookCalls("CONVERSATION_CONTROL"))
        assertEquals(listOf(mapOf("do" to "touch")), run.adb.hookCalls("GOAL_CONTROL"))
        assertTrue(run.adb.commands.none { it.startsWith("input ") })
        assertEquals("the end of the quiet time", EndQuietTime.toString())
    }

    @Test
    fun theSmallestLimitsPutBackTheLimitsThatKestrelAnsweredBeforeTheChange() {
        val kestrel = Limits()

        assertEquals(SetupResult.Ready, SmallestLimits.apply(scope))
        assertEquals("50/5/10", kestrel.stored)
        assertTrue(run.restores.runDownTo(0).isEmpty())
        assertEquals("500/60/1000", kestrel.stored)
    }

    // Task 6's review M1 and M2: only Kestrel's own answers decide the restore. A change that never reached Kestrel, after a
    // Ctrl+C between the restore's push and the change or after a refused change, leaves the limits as they were, so the
    // restore does nothing and asks no one for work by hand; a lost answer of the restore's set is asked again, because the
    // set does the same when it is sent twice.
    @Test
    fun theSmallestLimitsRestoreDoesNothingWhenCtrlCCameBeforeTheChange() {
        val kestrel = Limits()
        // Ctrl+C after the restore was pushed, while the step reads the limits before its change.
        run.restores.push("an earlier step", "") {}
        var ctrlC: Thread? = null
        kestrel.onCheck = { if (ctrlC == null) ctrlC = run.ctrlC() }

        assertThrows(Stopping::class.java) { SmallestLimits.apply(scope) }

        checkNotNull(ctrlC).join()
        assertEquals("500/60/1000", kestrel.stored)
        assertEquals(emptyList<String>(), kestrel.sets)
        assertTrue(run.console.warnings.toString(), run.console.warnings.none { "by hand" in it || "Conversations" in it })
    }

    @Test
    fun theSmallestLimitsRestoreDoesNothingAfterARefusedChangeAndRetriesALostSet() {
        val kestrel = Limits()
        kestrel.refuse = true

        assertThrows(HookError::class.java) { SmallestLimits.apply(scope) }
        assertTrue(run.restores.runDownTo(0).isEmpty())
        assertEquals("500/60/1000", kestrel.stored)

        kestrel.refuse = false
        SmallestLimits.apply(scope)
        run.adb.loseNext("hook.CONVERSATION_CONTROL.*'500/60/1000'")
        assertTrue(run.restores.runDownTo(0).isEmpty())
        assertEquals("500/60/1000", kestrel.stored)
        assertEquals(listOf("50/5/10", "500/60/1000", "500/60/1000"), kestrel.sets)
    }

    // Kestrel's stored limits as conversation-control do=limits sets them and do=limits-check reads them.
    private inner class Limits {
        var stored = "500/60/1000"
        val sets = mutableListOf<String>()
        var refuse = false
        var onCheck: () -> Unit = {}

        init {
            run.adb.hookWith("CONVERSATION_CONTROL") { extras ->
                when (extras["do"]) {
                    "limits-check" -> {
                        onCheck()
                        -1 to "limits=$stored"
                    }
                    else -> if (refuse) {
                        1 to "add --es limits <replies>/<minutes>/<credits>, or no limits for the page's smallest"
                    } else {
                        val was = stored
                        stored = extras["limits"] ?: "50/5/10"
                        sets += stored
                        -1 to "limits=$stored was=$was"
                    }
                }
            }
        }
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
    fun onlyAlarmAtPassesForTheOneAlarmClockThatTheRunLeftAtItsTime() {
        val phone = ClockPhone(run)
        assertEquals(SetupResult.Ready, ClockFree.apply(scope))
        fun passes() = OnlyAlarmAt(7, 30).passes(scope, HookAnswer.EMPTY)

        assertFalse(passes())
        // The goal's 7:00 alarm, which it turned off, is not in dumpsys alarm, which lists only enabled alarms.
        phone.alarmAt(7, 30)
        assertTrue(passes())
        phone.alarmAt(7, 0)
        assertFalse(passes())
    }

    // Task 6's review M3: each alarm is its own entry, so a second alarm of the run's at the same minute fails the check, and
    // an alarm in the setup's list from before the run is never the run's.
    @Test
    fun onlyAlarmAtFailsOnASecondAlarmAtTheSameMinuteAndCountsOnlyTheRunsAlarms() {
        val phone = ClockPhone(run)
        assertEquals(SetupResult.Ready, ClockFree.apply(scope))
        phone.alarmAt(7, 30)
        phone.alarmAt(7, 30)

        assertFalse(OnlyAlarmAt(7, 30).passes(scope, HookAnswer.EMPTY))

        // As if the first had been there before the run.
        val first = Alarms.parse(run.device.read("dumpsys alarm"))!!.of(ClockApp.GOOGLE).first { it.clock }.id
        scope.notes[ClockFree.BEFORE] = first
        assertTrue(OnlyAlarmAt(7, 30).passes(scope, HookAnswer.EMPTY))
    }

    // The whole-plan review of plan 7G (nit): an answer of voice-restore whose times are no numbers fails with a message
    // that names the pair, not with a NumberFormatException.
    @Test
    fun aVoiceRestoreAnswerWithoutNumbersFailsWithItsPairsName() {
        assertEquals(null, CrashGuardPassed.left(HookAnswer("restoredAgo=none guard=120"), CrashGuardPassed.MARGIN))
        val ago = assertThrows(IllegalStateException::class.java) { CrashGuardPassed.left(HookAnswer("restoredAgo=soon guard=120"), CrashGuardPassed.MARGIN) }
        assertEquals("voice-restore gave no number of seconds in restoredAgo=.", ago.message)
        val guard = assertThrows(IllegalStateException::class.java) { CrashGuardPassed.left(HookAnswer("restoredAgo=3 guard=long"), CrashGuardPassed.MARGIN) }
        assertEquals("voice-restore gave no number of seconds in guard=.", guard.message)
    }
}
