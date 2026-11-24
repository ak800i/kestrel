package dev.kestrel.eval.task

import dev.kestrel.eval.config.LocalConfig
import dev.kestrel.eval.device.DeviceKind
import dev.kestrel.eval.device.FakeAdb
import dev.kestrel.eval.hook.HookAnswer
import dev.kestrel.eval.run.Stopping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Gmail's inbox for the tests, as kestrel-api37's Gmail shows it: each row of [inbox], "sender, subject, snippet", is an
 * element whose label joins its parts with ", ", and its subject and snippet are elements of their own; the hooks match
 * them as [FakeScreen] says. A long press selects a row, and so does a tap while a row is selected. While a row is
 * selected, the toolbar's Delete removes every selected row, and the inbox opened in a new task drops the selection.
 * Send puts the test e-mail of [MailToSelf] for the marker "maple heron" into [pending], from which mail reaches the inbox
 * at a screen-has for which [arrives] is true; the next [deleteMisses] screen-has for Delete count none, as while
 * Gmail's toolbar still moves. The navigation drawer's Drafts row is "Drafts" with no draft and "Drafts, n" with n, beside
 * its title "Drafts"; [drafts] holds Drafts' rows, whose selection the toolbar's "Discard drafts" discards after its
 * question's OK. A row and the buttons are clickable, a row's subject and snippet are not, and screen-has' bounds are
 * those of the first element that it counts.
 */
class FakeGmail(adb: FakeAdb, val inbox: MutableList<String>, val drafts: MutableList<String> = mutableListOf()) {
    val selected = mutableSetOf<String>()
    val pending = mutableListOf<String>()
    var arrives: () -> Boolean = { true }
    var deleteMisses = 0
    private var view = View.INBOX

    private enum class View { INBOX, DRAWER, DRAFTS, QUESTION }

    private fun rows(list: List<String>): List<Pair<String, String?>> = list.flatMap { row -> listOf(row to row) + row.split(", ").drop(1).map { it to row } }

    // Each element's label and its row; the toolbar's buttons, the drawer and the question have none.
    private fun elements(): List<Pair<String, String?>> = when (view) {
        View.INBOX -> listOf("Open navigation drawer" to null) + (if (selected.isEmpty()) emptyList() else listOf("Delete" to null)) + rows(inbox)
        View.DRAWER -> listOf("Inbox" to null, (if (drafts.isEmpty()) "Drafts" else "Drafts, ${drafts.size}") to null, "Drafts" to null)
        View.DRAFTS -> listOf("Open navigation drawer" to null) + (if (selected.isEmpty()) emptyList() else listOf("Discard drafts" to null)) + rows(drafts)
        View.QUESTION -> listOf("Discard drafts from this conversation?" to null, "Cancel" to null, "OK" to null)
    }

    init {
        adb.on("^am start -W -f 0x10008000 -n com\\.google\\.android\\.gm/\\.ConversationListActivityGmail$") {
            selected.clear()
            view = View.INBOX
            ""
        }
        adb.hook("SCREEN_HAS") { extras ->
            if (pending.isNotEmpty() && arrives()) {
                inbox.addAll(pending)
                pending.clear()
            }
            val elements = elements()
            // A row and the buttons are clickable; a row's subject and snippet are not.
            val shown = if (extras["state"] == "clickable") elements.filter { (label, row) -> row == null || row == label } else elements
            val count = when {
                extras["label"] == "Delete" && selected.isNotEmpty() && deleteMisses > 0 -> {
                    deleteMisses--
                    0
                }
                // The drawer's row is a LinearLayout, and its title a TextView.
                extras["class"] == "LinearLayout" -> if (view == View.DRAWER) FakeScreen.has(listOf(elements[1].first), extras) else 0
                else -> FakeScreen.has(shown.map { it.first }, extras)
            }
            val first = shown.firstOrNull { FakeScreen.has(listOf(it.first), extras) == 1 }?.let { elements.indexOf(it) }
            val bounds = when {
                view == View.DRAWER && extras["label"] == "Drafts" && count >= 1 -> "0,1446,840,1573"
                count >= 1 && first != null -> "0,$first,1080,${first + 1}"
                else -> "none"
            }
            "count=$count app=${MailToSelf.GMAIL} bounds=$bounds"
        }
        adb.hook("SCREEN_TAP") hook@{ extras ->
            if (extras["label"] == "Send") {
                pending.add("me, Kestrel test maple heron, maple heron")
                return@hook "tapped=true count=1"
            }
            val elements = elements()
            val matches = FakeScreen.taps(elements.map { it.first }, extras)
            val index = matches.firstOrNull() ?: return@hook "tapped=false count=0"
            val (label, row) = elements[index]
            when {
                view == View.DRAWER -> if (label.startsWith("Drafts")) view = View.DRAFTS
                label == "Open navigation drawer" -> view = View.DRAWER
                label == "Discard drafts" -> view = View.QUESTION
                label == "OK" -> {
                    drafts.removeAll(selected)
                    selected.clear()
                    view = View.DRAFTS
                }
                label == "Cancel" -> view = View.DRAFTS
                row == null -> {
                    inbox.removeAll(selected)
                    selected.clear()
                }
                extras["long"] == "true" || selected.isNotEmpty() -> selected.add(row)
            }
            "tapped=true count=${matches.size}"
        }
    }
}

fun fakeGmail(adb: FakeAdb, inbox: MutableList<String>, drafts: MutableList<String> = mutableListOf()): FakeGmail = FakeGmail(adb, inbox, drafts)

/** The steps and checks of the SMS, e-mail and notification tasks (design D8, D9 and D17 of sub-project 6). */
class MessageStepsTest {
    private val none = HookAnswer.EMPTY
    private val config = LocalConfig.of(mapOf("gmailAddress" to "pera.test@example.com", "emulatorGmailAddress" to "pera.second@example.com"))

    @Test
    fun theTestSmsComesThroughTheEmulatorsConsoleAndLeavesByItsId() {
        for (before in listOf("ignore", null)) {
            val run = FakeRun()
            var deleted = false
            // Android 17 ignores the shell's delete of an SMS unless its app op WRITE_SMS allows it.
            var mode = before
            run.adb.on("^appops get com\\.android\\.shell WRITE_SMS$") { mode?.let { "WRITE_SMS: $it; time=+1h ago" } ?: "No operations." }
            run.adb.on("^appops set com\\.android\\.shell WRITE_SMS (\\w+)$") {
                mode = it.groupValues[1].takeIf { value -> value != "default" }
                ""
            }
            run.adb.on("^content query --uri content://sms/inbox --projection _id --where ") {
                if (!deleted && run.adb.hostCommands.any { it.take(3) == listOf("emu", "sms", "send") }) "Row: 0 _id=7" else "No result found."
            }
            run.adb.on("^content delete --uri content://sms --where '_id=7'$") {
                if (mode == "allow") deleted = true
                ""
            }

            assertEquals(SetupResult.Ready, EmuSms("+1 202 555 0177", "Kestrel test {marker}").apply(run.scope))
            assertTrue(run.adb.hostCommands.contains(listOf("emu", "sms", "send", "12025550177", "Kestrel test maple heron")))
            assertTrue(run.adb.commands.contains("content query --uri content://sms/inbox --projection _id --where 'body LIKE '\\''%maple heron%'\\'''"))
            assertTrue(run.restores.runAll().isEmpty())
            assertTrue(deleted)
            assertEquals(before, mode)
        }
        assertTrue(EmuSms("+1 202 555 0177", "Kestrel test {marker}").apply(FakeRun(DeviceKind.A71).scope) is SetupResult.Untestable)
    }

    @Test
    fun anAllowOfWriteSmsWhoseAnswerIsLostIsPutBackToo() {
        val run = FakeRun()
        var mode = "ignore"
        run.adb.on("^appops get com\\.android\\.shell WRITE_SMS$") { "WRITE_SMS: $mode; time=+1h ago" }
        // Each allow takes effect, and each of its answers is lost.
        run.adb.on("^appops set com\\.android\\.shell WRITE_SMS (\\w+)$") {
            mode = it.groupValues[1]
            if (mode == "allow") null else ""
        }

        assertThrows(Exception::class.java) { Sms.delete(run.scope, listOf(7L)) }
        assertEquals("ignore", mode)
        assertTrue(run.adb.commands.none { it.startsWith("content delete") })
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
    fun takingKestrelsNotificationAccessBackWaitsForTheSettingAndTurnsListeningBackOnAsItWas() {
        for (before in listOf("listening", "paused")) {
            val run = FakeRun()
            var listeners = "com.example.watch/com.example.watch.Listener"
            // Android 17 writes the setting a moment after disallow_listener, and Kestrel's process comes back with listening off.
            var lag = 0
            run.adb.on("^settings get secure enabled_notification_listeners$") {
                if (lag > 0) {
                    lag--
                    "$listeners:${NotificationAccess.LISTENER}"
                } else {
                    listeners
                }
            }
            var mode = before
            var silent = 0
            run.adb.on("^cmd notification (allow|disallow)_listener (\\S+)$") { match ->
                if (match.groupValues[1] == "allow") {
                    listeners = "$listeners:${match.groupValues[2]}"
                } else {
                    listeners = listeners.split(':').filter { it != match.groupValues[2] }.joinToString(":")
                    lag = 2
                    silent = 2
                    mode = "off"
                }
                ""
            }
            run.adb.on("dev\\.kestrel\\.hook\\.VOICE_STATE") {
                if (silent > 0) {
                    silent--
                    "Broadcasting: Intent { act=dev.kestrel.hook.VOICE_STATE }\nBroadcast completed: result=0"
                } else {
                    "Broadcasting: Intent { act=dev.kestrel.hook.VOICE_STATE }\nBroadcast completed: result=-1, data=\"mode=$mode work=idle\""
                }
            }
            run.adb.hook("VOICE") { extras ->
                if (extras["event"] == "toggle") mode = if (mode == "listening") "paused" else "listening"
                "sent"
            }

            assertEquals(SetupResult.Ready, NotificationAccess.apply(run.scope))
            assertTrue(run.restores.runAll().isEmpty())
            assertEquals(if (before == "listening") "listening" else "off", mode)
            assertEquals(mapOf("listener" to "off"), NotificationAccess.probe(run.scope))
            assertEquals(if (before == "listening") 1 else 0, run.adb.hookCalls("VOICE").count { it["event"] == "toggle" })
        }
    }

    @Test
    fun gmailDeletesTheRunsConversationAndOnKestrelApi37AlsoTheOlderTestEmails() {
        for (kind in DeviceKind.entries) {
            val run = FakeRun(kind)
            val inbox = mutableListOf(
                "me, Kestrel test cedar otter, cedar otter", "me, Reminder about maple heron, A reminder about maple heron", "GitHub, Kestrel tests failed", "Ana, Lunch",
            )
            fakeGmail(run.adb, inbox)

            GmailTrash.push(run.scope)
            assertTrue(kind.label, run.restores.runAll().isEmpty())

            val left = if (kind == DeviceKind.EMULATOR) listOf("GitHub, Kestrel tests failed", "Ana, Lunch") else listOf("me, Kestrel test cedar otter, cedar otter", "GitHub, Kestrel tests failed", "Ana, Lunch")
            assertEquals(kind.label, left, inbox)
            assertTrue(run.adb.commands.contains("am start -W -f 0x10008000 -n com.google.android.gm/.ConversationListActivityGmail"))
            assertTrue(run.adb.hookCalls("SCREEN_TAP").all { it["package"] == MailToSelf.GMAIL })
            assertEquals("input keyevent KEYCODE_HOME", run.adb.commands.last())
        }
    }

    @Test
    fun gmailDiscardsTheRunsDraftAloneAndOtherwiseSaysWhatToDoByHand() {
        val runs = "Important, Draft, Reminder about maple heron, A reminder about maple heron., 8:33 AM"
        val other = "Draft, Plans for the weekend, Notes, 8:00 AM"
        for (kind in DeviceKind.entries) {
            val run = FakeRun(kind)
            val drafts = mutableListOf(runs, other)
            fakeGmail(run.adb, mutableListOf("Ana, Lunch"), drafts)

            GmailTrash.push(run.scope)

            assertEquals(kind.label, emptyList<Any>(), run.restores.runAll())
            assertEquals(kind.label, listOf(other), drafts)
            assertEquals(kind.label, listOf("OK"), run.adb.hookCalls("SCREEN_TAP").mapNotNull { it["label"] }.filter { it == "OK" })
            assertEquals("input keyevent KEYCODE_HOME", run.adb.commands.last())
        }

        // No draft of the run: Drafts opens, and nothing is pressed there.
        val clean = FakeRun()
        val drafts = mutableListOf(other)
        fakeGmail(clean.adb, mutableListOf("Ana, Lunch"), drafts)
        GmailTrash.push(clean.scope)
        assertTrue(clean.restores.runAll().isEmpty())
        assertEquals(1, drafts.size)
        assertTrue(clean.adb.hookCalls("SCREEN_TAP").none { it["long"] == "true" || it["label"] == "Discard drafts" })

        // Two rows of the run's draft: neither is pressed, and the restore fails with the step by hand.
        val twice = FakeRun()
        val both = mutableListOf(runs, runs)
        fakeGmail(twice.adb, mutableListOf("Ana, Lunch"), both)
        GmailTrash.push(twice.scope)
        val failures = twice.restores.runAll()
        assertEquals(listOf("Gmail: in Drafts, discard the draft \"maple heron\"."), failures.map { it.byHand })
        assertEquals(2, both.size)
        assertTrue(twice.adb.hookCalls("SCREEN_TAP").none { it["long"] == "true" })
    }

    @Test
    fun aRowThatALiveModelWroteIsNamedByItsOneClickableElement() {
        // A live model writes its own subject and body, so only the row, the one clickable element, holds the marker once.
        val draft = "Important, Draft, Note to self: maple heron, Remember maple heron today, 12:15 PM"
        val email = "me, Note to self: maple heron, Remember maple heron today"
        for (kind in DeviceKind.entries) {
            val run = FakeRun(kind)
            val inbox = mutableListOf(email, "Ana, Lunch")
            val drafts = mutableListOf(draft, "Draft, Plans for the weekend, Notes, 8:00 AM")
            fakeGmail(run.adb, inbox, drafts)

            GmailTrash.push(run.scope)

            assertEquals(kind.label, emptyList<Any>(), run.restores.runAll())
            assertEquals(kind.label, listOf("Ana, Lunch"), inbox)
            assertEquals(kind.label, listOf("Draft, Plans for the weekend, Notes, 8:00 AM"), drafts)
            assertTrue(kind.label, run.adb.hookCalls("SCREEN_TAP").filter { it["long"] == "true" }.all { it["contains"] == "maple heron" })
        }
    }

    @Test
    fun aLostAnswerOfGmailsLongPressIsTriedOnceMoreInTheInboxOpenedAgain() {
        for (losses in 1..2) {
            val run = FakeRun(DeviceKind.A71)
            val inbox = mutableListOf("me, Kestrel test maple heron, maple heron", "Ana, Lunch")
            fakeGmail(run.adb, inbox)
            repeat(losses) { run.adb.loseNext("SCREEN_TAP") }

            GmailTrash.push(run.scope)
            val failures = run.restores.runAll()

            val presses = run.adb.hookCalls("SCREEN_TAP").filter { it["long"] == "true" }
            assertEquals(2, presses.size)
            if (losses == 1) {
                assertTrue(failures.isEmpty())
                assertEquals(listOf("Ana, Lunch"), inbox)
            } else {
                // Twice lost: the restore fails without a Delete, and the e-mail stays for the owner to delete.
                assertEquals(1, failures.size)
                assertEquals(2, inbox.size)
                assertTrue(run.adb.hookCalls("SCREEN_TAP").none { it["label"] == "Delete" })
            }
            // Twice for the inbox, and once for Drafts.
            assertEquals(3, run.adb.commands.count { it == "am start -W -f 0x10008000 -n com.google.android.gm/.ConversationListActivityGmail" })
        }
    }

    @Test
    fun theSweepStartsEachWordsTapsWithoutASelectionAndTapsOnlyTheExactDelete() {
        val run = FakeRun()
        val inbox = mutableListOf(
            "me, Kestrel test cedar otter, cedar otter",
            "GitHub, Repository deleted, Your repository was deleted",
            "Delete Bot, Weekly digest, Your weekly news",
            // A real e-mail: screen-has does not count it for "me, Kestrel test coral", but screen-tap's text matches it first.
            "me, Kestrel test corals and reefs, Notes about reefs",
            "me, Kestrel test coral lynx, coral lynx",
            "me, Kestrel test raven birch, raven birch",
        )
        val gmail = fakeGmail(run.adb, inbox)
        // cedar's Delete is not there yet when the taps look for it.
        gmail.deleteMisses = 1

        GmailTrash.push(run.scope)
        assertTrue(run.restores.runAll().isEmpty())

        assertEquals(
            listOf(
                "me, Kestrel test cedar otter, cedar otter", "GitHub, Repository deleted, Your repository was deleted", "Delete Bot, Weekly digest, Your weekly news",
                "me, Kestrel test corals and reefs, Notes about reefs", "me, Kestrel test coral lynx, coral lynx",
            ),
            inbox,
        )
        assertTrue(run.adb.hookCalls("SCREEN_TAP").none { it["contains"]?.contains("Delete", ignoreCase = true) == true })
    }

    @Test
    fun theRestoreWaitsAMinuteForATestEmailThatIsOnItsWayAndSaysSoWhenNoneCame() {
        for (late in listOf(4, 1000)) {
            val run = FakeRun(DeviceKind.A71)
            val inbox = mutableListOf("Ana, Lunch")
            val gmail = fakeGmail(run.adb, inbox)
            var polls = 0
            gmail.arrives = { ++polls >= late }
            // email-self's e-mail, which Kestrel sent during the run, is still on its way.
            gmail.pending.add("me, Reminder about maple heron, A reminder about maple heron")

            assertEquals(SetupResult.Ready, MailCleanup.apply(run.scope))
            // Kestrel's task ran, and confirmed the send.
            run.scope.tag = "t1"
            run.adb.hook("AGENT_TASK") { "state=done end=FINISHED steps=3 confirmed=1 unconfirmed=0" }
            assertTrue(run.restores.runAll().isEmpty())

            assertEquals(listOf("Ana, Lunch"), inbox)
            if (late == 4) {
                assertTrue(run.console.warnings.isEmpty())
            } else {
                assertEquals(1, run.console.warnings.size)
                assertTrue(run.console.warnings.single().contains("maple heron"))
                assertTrue(run.clock.time in 1.minutes..2.minutes)
            }
        }

        // email-self's task, which test mode ended before any send, or one that never started, sent no e-mail that the restore waits for.
        for (tag in listOf("t1", null)) {
            val run = FakeRun(DeviceKind.A71)
            fakeGmail(run.adb, mutableListOf("Ana, Lunch"))
            assertEquals(SetupResult.Ready, MailCleanup.apply(run.scope))
            run.scope.tag = tag
            run.adb.hook("AGENT_TASK") { "state=done end=TEST_MODE steps=1 confirmed=0 unconfirmed=0" }
            assertTrue(run.restores.runAll().isEmpty())
            assertTrue(run.clock.time < 1.minutes)
            assertTrue(run.console.warnings.isEmpty())
        }

        // A test e-mail that was never sent is not waited for.
        val run = FakeRun(DeviceKind.A71, config = config)
        fakeGmail(run.adb, mutableListOf("Ana, Lunch"))
        run.adb.on("^dumpsys activity activities$") { "  topResumedActivity=ActivityRecord{a1 u0 com.google.android.apps.nexuslauncher/.NexusLauncherActivity t3}" }
        assertThrows(SetupFailed::class.java) { MailToSelf.apply(run.scope) }
        val before = run.clock.time
        assertTrue(run.restores.runAll().isEmpty())
        assertTrue(run.clock.time - before < 1.minutes)
        assertTrue(run.console.warnings.isEmpty())
    }

    @Test
    fun ctrlCWhileTheTestEmailIsSentDeletesItOnceItArrivesAndOpensNoInboxAfterwards() {
        val run = FakeRun(DeviceKind.A71, config = config)
        val inbox = mutableListOf("Ana, Lunch")
        val gmail = fakeGmail(run.adb, inbox)
        run.adb.on("^dumpsys activity activities$") { "  topResumedActivity=ActivityRecord{9f3e2b1 u0 com.google.android.gm/.ComposeActivityGmail t12}" }
        // The e-mail takes three looks of the restore's to arrive, and only the restore's.
        var looks = 0
        gmail.arrives = { Thread.currentThread().name == "eval-restores" && ++looks >= 3 }
        var hook: Thread? = null
        var started = false
        // Ctrl+C while Gmail's compose screen opens, during MailToSelf's wait for it.
        run.clock.onSleep = {
            if (!started && Thread.currentThread().name != "eval-restores" && run.adb.commands.any { it.contains("SENDTO") }) {
                started = true
                hook = run.ctrlC()
                val deadline = System.nanoTime() + 5_000_000_000L
                while (hook!!.state != Thread.State.TERMINATED && hook!!.state != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) Thread.sleep(1)
            }
        }

        assertThrows(Stopping::class.java) { MailToSelf.apply(run.scope) }
        hook!!.join(10_000)

        // The Send was tapped once, before the restore, which waited for the e-mail and deleted it.
        assertEquals(1, run.adb.hookCalls("SCREEN_TAP").count { it["label"] == "Send" })
        assertEquals(listOf("Ana, Lunch"), inbox)
        assertTrue(gmail.pending.isEmpty())
        assertEquals(emptyList<String>(), run.console.warnings)
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
