package dev.kestrel.eval.task

import dev.kestrel.eval.device.DeviceKind
import dev.kestrel.eval.device.Dumps
import dev.kestrel.eval.hook.Extra
import dev.kestrel.eval.hook.HookAnswer
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The SMS provider's inbox through `content`: the `_id`s of the messages whose body holds a text, deleted by `_id`. Android
 * 17 ignores the shell's delete without a word while the shell's app op WRITE_SMS is "ignore", so [delete] allows it for
 * the deletes and then puts back the mode that it read before.
 */
object Sms {
    private const val SHELL = "com.android.shell"

    fun ids(scope: RunScope, text: String): List<Long> =
        Rows.parse(scope.device.read("content query --uri content://sms/inbox --projection _id --where ${shellQuoted("body LIKE '%$text%'")}")).mapNotNull { it["_id"]?.toLongOrNull() }

    fun delete(scope: RunScope, ids: List<Long>) {
        if (ids.isEmpty()) return
        val before = mode(scope)
        try {
            if (before != "allow") scope.device.idempotent("appops set $SHELL WRITE_SMS allow")
            for (id in ids) scope.device.idempotent("content delete --uri content://sms --where ${shellQuoted("_id=$id")}")
        } finally {
            if (before != "allow") scope.device.idempotent("appops set $SHELL WRITE_SMS $before")
        }
        check(mode(scope) == before) { "The shell's app op WRITE_SMS is not $before again; run: adb shell appops set $SHELL WRITE_SMS $before" }
    }

    private fun mode(scope: RunScope): String = Dumps.appOpMode(scope.device.read("appops get $SHELL WRITE_SMS"), "WRITE_SMS")
}

/**
 * An SMS from [number] with [text] in the inbox, sent through the emulator's console (`sms send`), which only
 * kestrel-api37 has (design D8 of sub-project 6); [text] may hold the run's placeholders. Its restore, pushed before the
 * SMS exists, deletes every SMS whose body holds the run's marker by its `_id`, and checks; the setup waits up to 30 s
 * for the SMS.
 */
data class EmuSms(val number: String, val text: String) : Setup {
    override fun apply(scope: RunScope): SetupResult {
        if (scope.device.kind != DeviceKind.EMULATOR) return SetupResult.Untestable("only kestrel-api37 has an emulator console that sends an SMS")
        val marker = scope.values.marker
        val body = scope.values.fill(text)
        val byHand = "Messages: delete the SMS \"$body\"."
        scope.restores.push("the test SMS", byHand) {
            Sms.delete(scope, Sms.ids(scope, marker))
            check(Sms.ids(scope, marker).isEmpty()) { "The test SMS is still in the inbox." }
        }
        scope.restores.change("the test SMS sent", byHand) { scope.device.emu(listOf("sms", "send", number.filter { it.isDigit() }, body)) }
        repeat(15) {
            if (Sms.ids(scope, marker).isNotEmpty()) return SetupResult.Ready
            scope.clock.sleep(2.seconds)
        }
        throw SetupFailed("The test SMS did not reach the inbox.")
    }
}

/**
 * The run's test SMS deleted between two turns (design 15.3 of sub-project 7: search-past), so that a later command can
 * learn its words only from the conversation that read it; [EmuSms]'s restore, pushed before the SMS existed, still
 * checks that none is left.
 */
data object DeleteTestSms : TurnAction {
    override fun run(scope: RunScope) {
        val marker = scope.values.marker
        scope.restores.change("the test SMS deleted", "Messages: delete the SMS that holds \"$marker\".") { Sms.delete(scope, Sms.ids(scope, marker)) }
        check(Sms.ids(scope, marker).isEmpty()) { "The test SMS is still in the inbox." }
    }

    override fun toString() = "the test SMS deleted"
}

/**
 * notification-read's notification (design D17 of sub-project 6): `:testapp`'s NotificationActivity posts "Kestrel test
 * {marker}", after a restore that cancels it (`--ez cancel true`); the setup waits up to 10 s until `dumpsys
 * notification` lists a notification of `:testapp`.
 */
data object TestNotification : Setup {
    private const val ACTIVITY = "${Fixtures.PACKAGE}/.NotificationActivity"
    private val RECORD = Regex("NotificationRecord\\(.*\\bpkg=${Regex.escape(Fixtures.PACKAGE)}(\\s|$)")

    override fun apply(scope: RunScope): SetupResult {
        val byHand = "Run: adb -s ${scope.device.serial} shell am start -n $ACTIVITY --ez cancel true"
        scope.restores.push("the test notification cancelled", byHand) { scope.device.idempotent("am start -W -n $ACTIVITY --ez cancel true") }
        scope.restores.change("the test notification posted", byHand) {
            scope.device.idempotent("am start -W -n $ACTIVITY ${Extra.Text("marker", scope.values.marker).shell()}")
        }
        repeat(10) {
            if (scope.device.read("dumpsys notification").any { RECORD.containsMatchIn(it) }) return SetupResult.Ready
            scope.clock.sleep(1.seconds)
        }
        throw SetupFailed("The test notification did not show.")
    }
}

/** The device's own Gmail address from the local configuration in `{address}`, for email-self (design D8); it changes nothing. */
data object OwnAddress : Setup {
    override fun apply(scope: RunScope): SetupResult {
        scope.values.address = scope.config.address(scope.device.kind)
            ?: return SetupResult.Untestable("corpus\\owner\\a71-config.json gives no address of this device's Gmail account")
        return SetupResult.Ready
    }
}

/**
 * email-self's restore, pushed before the run: Gmail's own deletion of the conversation that holds the run's marker
 * ([GmailTrash]), which waits for the e-mail only when Kestrel's task may have sent it: when the task confirmed an action
 * or ran one unconfirmed, or when its end cannot be read. A task that test mode ended before any send, or a run that
 * gave no task, sent none, so the restore neither waits for it nor warns that it never came.
 */
data object MailCleanup : Setup {
    override fun apply(scope: RunScope): SetupResult {
        GmailTrash.push(scope)
        // Pushed after GmailTrash's restore, so that it runs before it, once the task has ended.
        scope.restores.push("the wait for the test e-mail", "None.") { if (sent(scope)) GmailTrash.expect(scope) }
        return SetupResult.Ready
    }

    private fun sent(scope: RunScope): Boolean {
        val tag = scope.tag ?: return false
        val answer = try {
            scope.hooks.agentTask(tag)
        } catch (e: Exception) {
            return true
        }
        if (answer["state"] != "done") return true
        return answer.int("confirmed") > 0 || answer.int("unconfirmed") > 0
    }
}

/** The run's e-mail reached Gmail's inbox within 2 minutes: the inbox, opened in a new task, shows the run's marker (design D8). */
data object MailArrived : Check {
    override fun passes(scope: RunScope, answer: HookAnswer): Boolean {
        GmailTrash.openInbox(scope)
        val deadline = scope.clock.elapsed() + 2.minutes
        while (true) {
            if (GmailTrash.shows(scope, scope.values.marker)) return true
            if (scope.clock.elapsed() > deadline) return false
            scope.clock.sleep(5.seconds)
        }
    }
}

/** No e-mail of the run reached Gmail's inbox: the inbox, opened in a new task, does not show the run's marker; email-self's known gap. */
data object NoMail : Check {
    override fun passes(scope: RunScope, answer: HookAnswer): Boolean {
        GmailTrash.openInbox(scope)
        return !GmailTrash.shows(scope, scope.values.marker)
    }
}
