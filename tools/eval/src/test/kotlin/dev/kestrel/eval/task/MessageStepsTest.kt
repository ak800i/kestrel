package dev.kestrel.eval.task

import dev.kestrel.eval.config.LocalConfig
import dev.kestrel.eval.device.DeviceKind
import dev.kestrel.eval.device.FakeAdb
import dev.kestrel.eval.hook.HookAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Gmail's inbox for the tests: [inbox] holds the labels of its rows, a long press selects the first row that holds its
 * text, Delete removes the selected row, and Send adds the test e-mail of [MailToSelf] for the marker "maple heron".
 */
fun fakeGmail(adb: FakeAdb, inbox: MutableList<String>) {
    var selected: String? = null
    adb.hook("SCREEN_HAS") { extras ->
        val contains = extras["contains"]
        val count = when {
            extras["label"] == "Delete" -> if (selected != null) 1 else 0
            contains != null -> inbox.count { it.contains(contains, ignoreCase = true) }
            else -> 0
        }
        "count=$count app=${MailToSelf.GMAIL}"
    }
    adb.hook("SCREEN_TAP") { extras ->
        when (extras["label"]) {
            "Send" -> inbox.add("Pera, Kestrel test maple heron, maple heron")
            "Delete" -> {
                inbox.remove(selected)
                selected = null
            }
            else -> selected = inbox.first { it.contains(extras.getValue("contains"), ignoreCase = true) }
        }
        "tapped=true count=1"
    }
}

/** The steps and checks of the SMS, e-mail and notification tasks (design D8, D9 and D17 of sub-project 6). */
class MessageStepsTest {
    private val none = HookAnswer.EMPTY
    private val config = LocalConfig.of(mapOf("gmailAddress" to "pera.test@example.com", "emulatorGmailAddress" to "pera.second@example.com"))

    @Test
    fun theTestSmsComesThroughTheEmulatorsConsoleAndLeavesByItsId() {
        val run = FakeRun()
        var deleted = false
        run.adb.on("^content query --uri content://sms/inbox --projection _id --where ") {
            if (!deleted && run.adb.hostCommands.any { it.take(3) == listOf("emu", "sms", "send") }) "Row: 0 _id=7" else "No result found."
        }
        run.adb.on("^content delete --uri content://sms --where '_id=7'$") {
            deleted = true
            ""
        }

        assertEquals(SetupResult.Ready, EmuSms("+1 202 555 0177", "Kestrel test {marker}").apply(run.scope))
        assertTrue(run.adb.hostCommands.contains(listOf("emu", "sms", "send", "12025550177", "Kestrel test maple heron")))
        assertTrue(run.adb.commands.contains("content query --uri content://sms/inbox --projection _id --where 'body LIKE '\\''%maple heron%'\\'''"))
        assertTrue(run.restores.runAll().isEmpty())
        assertTrue(deleted)
        assertTrue(EmuSms("+1 202 555 0177", "Kestrel test {marker}").apply(FakeRun(DeviceKind.A71).scope) is SetupResult.Untestable)
    }

    @Test
    fun theTestNotificationIsPostedAndCancelledAndKestrelsNotificationAccessIsOnOnlyForTheRun() {
        val run = FakeRun()
        var posted = false
        run.adb.on("^am start -W -n dev\\.kestrel\\.testapp/\\.NotificationActivity (.*)$") {
            posted = it.groupValues[1] != "--ez cancel true"
            ""
        }
        run.adb.on("^dumpsys notification$") { if (posted) "  NotificationRecord(0x0b2: pkg=dev.kestrel.testapp user=UserHandle{0} id=3)" else "" }
        var listeners = "com.example.watch/com.example.watch.Listener"
        run.adb.on("^settings get secure enabled_notification_listeners$") { listeners }
        run.adb.on("^cmd notification (allow|disallow)_listener (\\S+)$") { match ->
            listeners = if (match.groupValues[1] == "allow") "$listeners:${match.groupValues[2]}" else listeners.split(':').filter { it != match.groupValues[2] }.joinToString(":")
            ""
        }

        assertEquals(mapOf("listener" to "off"), NotificationAccess.probe(run.scope))
        assertEquals(SetupResult.Ready, NotificationAccess.apply(run.scope))
        assertEquals(SetupResult.Ready, TestNotification.apply(run.scope))
        assertTrue(run.adb.commands.contains("am start -W -n dev.kestrel.testapp/.NotificationActivity --es marker 'maple heron'"))
        assertEquals(mapOf("listener" to "on"), NotificationAccess.probe(run.scope))
        assertTrue(run.restores.runAll().isEmpty())
        assertFalse(posted)
        assertEquals("com.example.watch/com.example.watch.Listener", listeners)
    }

    @Test
    fun gmailDeletesTheRunsConversationAndOnKestrelApi37AlsoTheOlderTestEmails() {
        for (kind in DeviceKind.entries) {
            val run = FakeRun(kind)
            val inbox = mutableListOf(
                "Pera, Kestrel test cedar otter, cedar otter", "Pera, Reminder about maple heron, A reminder about maple heron", "GitHub, Kestrel tests failed", "Ana, Lunch",
            )
            fakeGmail(run.adb, inbox)

            GmailTrash.push(run.scope)
            assertTrue(kind.label, run.restores.runAll().isEmpty())

            val left = if (kind == DeviceKind.EMULATOR) listOf("GitHub, Kestrel tests failed", "Ana, Lunch") else listOf("Pera, Kestrel test cedar otter, cedar otter", "GitHub, Kestrel tests failed", "Ana, Lunch")
            assertEquals(kind.label, left, inbox)
            assertTrue(run.adb.commands.contains("am start -W -f 0x10008000 -n com.google.android.gm/.ConversationListActivityGmail"))
            assertTrue(run.adb.hookCalls("SCREEN_TAP").all { it["package"] == MailToSelf.GMAIL })
            assertEquals("input keyevent KEYCODE_HOME", run.adb.commands.last())
        }
    }

    @Test
    fun theMailArrivesWhenTheInboxShowsTheMarkerWithinTwoMinutes() {
        val run = FakeRun(config = config)
        assertEquals(SetupResult.Ready, OwnAddress.apply(run.scope))
        assertEquals("pera.second@example.com", run.scope.values.address)
        assertTrue(OwnAddress.apply(FakeRun().scope) is SetupResult.Untestable)
        var polls = 0
        var arrives = 3
        run.adb.hook("SCREEN_HAS") { "count=${if (++polls >= arrives) 1 else 0} app=${MailToSelf.GMAIL}" }

        assertTrue(MailArrived.passes(run.scope, none))
        polls = 0
        arrives = 1000
        assertFalse(MailArrived.passes(run.scope, none))
        assertTrue(run.clock.time >= 2.minutes)
    }
}
