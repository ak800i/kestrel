package dev.kestrel.eval.task

import dev.kestrel.eval.run.KestrelRestart
import dev.kestrel.eval.run.Reason
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The multi-turn tasks of design 15.3 of sub-project 7 against a scripted Kestrel ([FakeConversation]), with every restore run. */
class ConversationTasksTest {
    /** kestrel-api37 with dark mode off and a 1-minute screen timeout, which Kestrel's commands change as their words ask. */
    private fun settingsBench(): Pair<TaskBench, FakeConversation> {
        val bench = TaskBench()
        var night = "no"
        bench.adb.settings["system/screen_off_timeout"] = "60000"
        bench.adb.on("^cmd uimode night$") { "Night mode: $night" }
        bench.adb.on("^cmd uimode night (yes|no)$") {
            night = it.groupValues[1]
            ""
        }
        val kestrel = FakeConversation(bench.adb)
        kestrel.onInput = { input ->
            if (input.endsWith("turn on dark mode")) night = "yes"
            if (input.endsWith("set the screen timeout to 2 minutes")) bench.adb.settings["system/screen_off_timeout"] = "120000"
        }
        return bench to kestrel
    }

    /** kestrel-api37's inbox, which holds the run's test SMS from its send until a delete. */
    private fun smsBench(): TaskBench {
        val bench = TaskBench()
        var held = false
        bench.adb.granted["dev.kestrel"] = mutableSetOf("android.permission.READ_SMS")
        bench.adb.hostOut = { args ->
            if (args.take(3) == listOf("emu", "sms", "send")) held = true
            ""
        }
        bench.adb.on("^content query --uri content://sms/inbox ") { if (held) "Row: 0 _id=7" else "No result found." }
        bench.adb.on("^content delete --uri content://sms ") {
            held = false
            ""
        }
        bench.adb.on("^appops get com\\.android\\.shell WRITE_SMS$") { "WRITE_SMS: allow; time=+1h ago" }
        return bench
    }

    @Test
    fun followupSettingsSpeaksItsSecondCommandInTheWindowAndQuietTimeEndsGivesItInANewConversation() {
        val (bench, kestrel) = settingsBench()

        assertEquals(Reason.OK, bench.run(ConversationTasks.FOLLOWUP_SETTINGS).reason)
        assertEquals(listOf("spoken:turn on dark mode", "spoken:and set the screen timeout to 2 minutes"), kestrel.inputs)
        assertEquals("and set the screen timeout to 2 minutes", bench.adb.hookCalls("VOICE")[1]["text"])
        bench.assertPutBack()

        val (quiet, closing) = settingsBench()
        assertEquals(Reason.OK, quiet.run(ConversationTasks.QUIET_TIME_ENDS).reason)
        assertEquals(listOf("spoken:turn on dark mode", "spoken:set the screen timeout to 2 minutes"), closing.inputs)
        // The task's end of the quiet time, then the run's last restore: the second conversation closed, and both deleted.
        assertEquals(listOf(mapOf("do" to "expire-typed"), mapOf("do" to "expire-typed"), mapOf("do" to "delete-typed")), quiet.adb.hookCalls("CONVERSATION_CONTROL"))
        assertEquals(listOf(1, 2), closing.deleted)
        quiet.assertPutBack()

        // Without the follow-up window the second command is never spoken, and the run fails.
        val (shut, silent) = settingsBench()
        silent.windows = false
        assertEquals(Reason.CHECK, shut.run(ConversationTasks.FOLLOWUP_SETTINGS).reason)
        assertEquals(listOf("spoken:turn on dark mode"), silent.inputs)
        shut.assertPutBack()
    }

    @Test
    fun typedContactThenMessageTypesBothCommandsAndConfirmsOnTheCardOverTheApp() {
        val bench = TaskBench()
        val providers = FakeProviders(bench.adb)
        bench.adb.granted["dev.kestrel"] = mutableSetOf("android.permission.READ_CONTACTS")
        bench.adb.hook("SCREEN_HAS") { "count=1 app=dev.kestrel.testapp" }
        var presses = ""
        bench.adb.on("^run-as dev.kestrel.testapp sh -c") { presses }
        val kestrel = FakeConversation(bench.adb)
        kestrel.planned += FakeConversation.Command()
        kestrel.planned += FakeConversation.Command(
            end = FakeConversation.line(confirmed = 1),
            asks = listOf("asking=confirm confirming=typing_screen_text question=none", "asking=confirm confirming=sending question=none"),
        )
        kestrel.onInput = { input -> if (input.startsWith("typed:Send it")) presses = "send 1795086000000" }

        assertEquals(Reason.OK, bench.run(ConversationTasks.STAND_IN_TYPED_CONTACT_THEN_MESSAGE).reason)

        assertEquals(listOf("typed:Find the number of Ana Test.", "typed:Send it to me in Telegram's Saved Messages."), kestrel.inputs)
        // The first reply is checked for the number's national digits, as contact-number checks it, so a reply without the
        // country code passes too; both commands are in one conversation.
        assertEquals(listOf("2025550160"), bench.adb.hookCalls("AGENT_TASK").mapNotNull { it["digits"] })
        assertEquals(2, kestrel.closed)
        assertEquals(listOf("confirm/card", "confirm/card"), kestrel.answers)
        assertTrue(bench.adb.hookCalls("VOICE").isEmpty())
        assertTrue(providers.live().isEmpty())
        bench.assertPutBack()
    }

    @Test
    fun searchPastAsksAfterTheSmsLeftAndContinuePastSwitchesBackToTheConversationThatReadIt() {
        val bench = smsBench()
        val kestrel = FakeConversation(bench.adb)
        var deletedFirst = false
        kestrel.onInput = { input ->
            if (input.startsWith("spoken:what was")) deletedFirst = bench.adb.commands.any { it.startsWith("content delete") }
        }

        assertEquals(Reason.OK, bench.run(ConversationTasks.SEARCH_PAST).reason)
        assertEquals(listOf("spoken:read my latest text message", "spoken:what was the code word in the text message that you read me before?"), kestrel.inputs)
        assertTrue(deletedFirst)
        bench.assertPutBack()

        val switching = smsBench()
        val past = FakeConversation(switching.adb)
        past.planned += FakeConversation.Command()
        past.planned += FakeConversation.Command(continues = true, asks = listOf("asking=yes-no confirming=none question=switch"))
        assertEquals(Reason.OK, switching.run(ConversationTasks.CONTINUE_PAST).reason)
        assertEquals(
            listOf("spoken:read my latest SMS", "spoken:continue the conversation about my latest SMS", "spoken:what was the code word in it?"),
            past.inputs,
        )
        assertEquals("what was the code word in it?", switching.adb.hookCalls("VOICE").last()["text"])
        assertEquals(listOf("yes"), past.answers)
        // The run's last restore closed the continued conversation, which held both commands.
        assertEquals(2, past.closed)
        switching.assertPutBack()
    }

    /** kestrel-api37 with `:testapp`'s messenger, whose fixtures record Kestrel's send once the returned function runs. */
    private fun messengerBench(): Pair<TaskBench, () -> Unit> {
        val bench = TaskBench()
        bench.adb.hook("SCREEN_HAS") { "count=1 app=dev.kestrel.testapp" }
        var presses = ""
        bench.adb.on("^run-as dev.kestrel.testapp sh -c") { presses }
        return bench to { presses = "send 1795086000000" }
    }

    @Test
    fun steerWhileRunningSteersTheAlarmThatActsAndLeavesOneAlarmAtTheNewTime() {
        val bench = TaskBench()
        val phone = ClockPhone(bench.run)
        val kestrel = FakeConversation(bench.adb)
        kestrel.planned += FakeConversation.Command(until = FakeConversation.Until.STEERED)
        kestrel.onInput = { input -> if (input == "steered:make it 7:30 instead") phone.alarmAt(7, 30) }

        assertEquals(Reason.OK, bench.run(ConversationTasks.STEER_WHILE_RUNNING).reason)

        assertEquals(listOf("spoken:set an alarm for 7:00 tomorrow morning", "steered:make it 7:30 instead"), kestrel.inputs)
        assertEquals("Kestrel, make it 7:30 instead", bench.adb.hookCalls("VOICE")[1]["text"])
        assertEquals(0, phone.clocks())
        bench.assertPutBack()
    }

    @Test
    fun limitContinueSaysYesThenLetsTheRoundPauseAndInterruptContinueTouchesTheGoalThatActs() {
        val (bench, _) = messengerBench()
        val kestrel = FakeConversation(bench.adb)
        val limit = "asking=yes-no confirming=none question=limit"
        kestrel.planned += FakeConversation.Command(end = FakeConversation.line("PAUSED_AT_LIMIT"), asks = listOf(limit, limit))

        assertEquals(Reason.OK, bench.run(ConversationTasks.LIMIT_CONTINUE).reason)
        assertEquals(listOf("spoken:turn on hotspot", "spoken:continue"), kestrel.inputs)
        assertEquals(listOf("yes"), kestrel.answers)
        assertEquals(listOf(mapOf("do" to "limits"), mapOf("do" to "limits", "limits" to "500/60/1000")), bench.adb.hookCalls("CONVERSATION_CONTROL").filter { it["do"] == "limits" })
        bench.assertPutBack()

        val (touched, sendTouched) = messengerBench()
        val interrupted = FakeConversation(touched.adb)
        interrupted.planned += FakeConversation.Command(end = FakeConversation.line("INTERRUPTED"), until = FakeConversation.Until.TOUCHED, checklist = "1/2")
        interrupted.planned += FakeConversation.Command(end = FakeConversation.line(confirmed = 1), asks = listOf("asking=confirm confirming=sending question=none"))
        interrupted.onInput = { input -> if (input == "spoken:continue") sendTouched() }

        assertEquals(Reason.OK, touched.run(ConversationTasks.INTERRUPT_CONTINUE).reason)
        assertEquals(listOf("spoken:send Ana a Telegram message that I'm 10 minutes late for maple heron", "spoken:continue"), interrupted.inputs)
        assertEquals(listOf(mapOf("do" to "touch")), touched.adb.hookCalls("GOAL_CONTROL"))
        assertEquals(listOf("confirm"), interrupted.answers)
        touched.assertPutBack()
    }

    @Test
    fun resumeAfterDeathEndsKestrelsProcessWhileTheGoalActsAndContinuesTheHeldGoalWithTheNextScript() {
        val (bench, send) = messengerBench()
        val kestrel = FakeConversation(bench.adb)
        kestrel.planned += FakeConversation.Command(until = FakeConversation.Until.CRASHED, checklist = "1/2")
        kestrel.planned += FakeConversation.Command(end = FakeConversation.line(confirmed = 1), reads = 2, checklist = "1/2", asks = listOf("asking=confirm confirming=sending question=none"))
        kestrel.onInput = { input -> if (input == "spoken:continue") send() }

        assertEquals(Reason.OK, bench.run(ConversationTasks.RESUME_AFTER_DEATH).reason)

        assertEquals(4101, kestrel.pid)
        assertEquals(mapOf("text" to KestrelRestart.CRASH_TEXT), bench.adb.hookCalls("CRASH").single())
        assertEquals(listOf(mapOf("file" to "task.json"), mapOf("file" to "task.json"), mapOf("clear" to "true")), bench.adb.hookCalls("AGENT_SCRIPT"))
        assertEquals(1, bench.adb.hookCalls("CONVERSATION_CONTROL").count { it["do"] == "restore-check" })
        // The self-chat aliases were gone with the process, and the restart set them again (ProcessSetup).
        assertEquals(2, bench.adb.hookCalls("AGENT_SETTINGS").count { it["selfChatAliases"] == "dev.kestrel.testapp=org.telegram.messenger" })
        assertEquals(listOf("confirm"), kestrel.answers)
        // The notes of the hooks' own conversations outlived the process, so the run's conversation was deleted.
        assertEquals(listOf(1), kestrel.deleted)
        bench.assertPutBack()
    }

    // Kestrel's guard (main spec 8.5) keeps it off after a crash soon after a restore turned it on, so the run waits it out first.
    @Test
    fun resumeAfterDeathWaitsOutKestrelsGuardAgainstACrashLoopBeforeItsFirstCommand() {
        val (bench, send) = messengerBench()
        val clock = bench.run.clock
        val kestrel = FakeConversation(bench.adb, clock::elapsed)
        val restored = clock.elapsed()
        kestrel.restoredAt = restored
        kestrel.planned += FakeConversation.Command(until = FakeConversation.Until.CRASHED, checklist = "1/2")
        kestrel.planned += FakeConversation.Command(end = FakeConversation.line(confirmed = 1), reads = 2, checklist = "1/2", asks = listOf("asking=confirm confirming=sending question=none"))
        var first: Duration? = null
        kestrel.onInput = { input ->
            if (first == null) first = clock.elapsed()
            if (input == "spoken:continue") send()
        }

        assertEquals(Reason.OK, bench.run(ConversationTasks.RESUME_AFTER_DEATH).reason)

        assertTrue(checkNotNull(first) - restored >= FakeConversation.GUARD_SECONDS.seconds + CrashGuardPassed.MARGIN)
        bench.assertPutBack()
    }
}
