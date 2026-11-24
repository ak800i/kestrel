package dev.kestrel.eval.task

import dev.kestrel.eval.config.LocalConfig
import dev.kestrel.eval.device.DeviceKind
import dev.kestrel.eval.run.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** sms-read, email-self and notification-read against a scripted device that passes and one that fails, with every restore run (design D8 and D9 of sub-project 6). */
class MessageTasksTest {
    private val config = LocalConfig.of(mapOf("gmailAddress" to "pera.test@example.com", "emulatorGmailAddress" to "pera.second@example.com"))

    @Test
    fun smsReadRunsOnKestrelApi37OnlyAndLeavesNoTestSms() {
        val bench = TaskBench()
        var deletes = 0
        fun sends() = bench.adb.hostCommands.count { it.take(3) == listOf("emu", "sms", "send") }
        bench.adb.on("^content query --uri content://sms/inbox ") { if (sends() > deletes) "Row: 0 _id=${sends()}" else "No result found." }
        bench.adb.on("^content delete --uri content://sms ") {
            deletes++
            ""
        }

        assertEquals(setOf(DeviceKind.EMULATOR), MessageTasks.SMS_READ.devices)
        assertEquals(Reason.OK, bench.run(MessageTasks.SMS_READ).reason)
        bench.replyMarker = "0"
        assertEquals(Reason.CHECK, bench.run(MessageTasks.SMS_READ).reason)
        assertEquals(2, deletes)
        bench.assertPutBack()
        assertTrue(bench.adb.commands.contains("pm revoke dev.kestrel android.permission.READ_SMS"))
    }

    @Test
    fun emailSelfIsAKnownGapThatPassesWhenTestModeStopsKestrelsSendAndLeavesNoTestEmail() {
        val bench = TaskBench(config = config)
        val inbox = mutableListOf("Ana, Lunch")
        fakeGmail(bench.adb, inbox)
        bench.adb.on("^dumpsys activity activities$") { "  topResumedActivity=ActivityRecord{9f3e2b1 u0 com.google.android.gm/.ComposeActivityGmail t12}" }
        var sends = false
        bench.adb.hook("VOICE") { extras ->
            if (sends && extras["text"] == "Kestrel, email me a reminder about maple heron") inbox.add("me, Reminder about maple heron, A reminder about maple heron")
            "sent"
        }
        // Test mode blocks Kestrel's Send in Gmail until Gmail's self-send rule is pinned, after the typing was confirmed.
        val stopped = "state=done end=TEST_MODE steps=2 workMs=9000 credits=0.00 confirmed=1 unconfirmed=0 shareItems=0 beforeTestMode=0 model=scripted"
        bench.end = stopped

        assertEquals(setOf("typing_screen_text", "sending"), MessageTasks.EMAIL_SELF.answers.confirm)
        assertEquals(VerdictRule.KnownGap, MessageTasks.EMAIL_SELF.verdict)
        assertEquals(1, MessageTasks.EMAIL_SELF.runsAtMost)
        assertTrue(MessageTasks.EMAIL_SELF.gap!!.contains("Gmail's self-send rule"))
        assertEquals(Reason.OK, bench.run(MessageTasks.EMAIL_SELF).reason)
        assertEquals(mapOf("add" to "true", "key" to "my e-mail address", "text" to "pera.second@example.com"), bench.adb.hookCalls("AGENT_MEMORY").first { it["add"] == "true" })
        assertEquals(listOf("Ana, Lunch"), inbox)
        // An e-mail that left after all fails the run, and leaves too.
        sends = true
        assertEquals(Reason.CHECK, bench.run(MessageTasks.EMAIL_SELF).reason)
        assertEquals(listOf("Ana, Lunch"), inbox)
        // A task that finished is no pass of the known gap.
        sends = false
        bench.end = stopped.replace("TEST_MODE", "FINISHED")
        assertEquals(Reason.END, bench.run(MessageTasks.EMAIL_SELF).reason)
        bench.assertPutBack()
        assertTrue(MessageTasks.EMAIL_SELF.rehearsal is Rehearsal.SetupOnly)
    }

    @Test
    fun notificationReadNeedsTheTestAppAndLeavesNoNotificationOrAccess() {
        val bench = TaskBench()
        var posted = false
        bench.adb.on("^am start -W -n dev\\.kestrel\\.testapp/\\.NotificationActivity (.*)$") {
            posted = it.groupValues[1] != "--ez cancel true"
            ""
        }
        bench.adb.on("^dumpsys notification$") { if (posted) "  NotificationRecord(0x0b2: pkg=dev.kestrel.testapp user=UserHandle{0} id=3)" else "" }
        var listeners = ""
        bench.adb.on("^settings get secure enabled_notification_listeners$") { listeners.ifEmpty { "null" } }
        bench.adb.on("^cmd notification (allow|disallow)_listener (\\S+)$") { match ->
            listeners = if (match.groupValues[1] == "allow") match.groupValues[2] else ""
            ""
        }

        assertEquals(Reason.OK, bench.run(MessageTasks.NOTIFICATION_READ).reason)
        bench.replyMarker = "0"
        assertEquals(Reason.CHECK, bench.run(MessageTasks.NOTIFICATION_READ).reason)
        bench.assertPutBack()
        assertFalse(posted)
        assertEquals("", listeners)
        assertTrue(bench.adb.commands.contains("pm grant dev.kestrel.testapp android.permission.POST_NOTIFICATIONS"))
        assertTrue(MessageTasks.NOTIFICATION_READ.needsTestApp)
        assertFalse(MessageTasks.EMAIL_SELF.needsTestApp)
        assertTrue(StandIns.READ.needsTestApp)
    }
}
