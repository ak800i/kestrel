package dev.kestrel.eval.task

import dev.kestrel.eval.device.DeviceKind
import dev.kestrel.eval.device.Dumps
import dev.kestrel.eval.hook.HookLost
import dev.kestrel.eval.run.Markers
import java.net.URLEncoder
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * read-email's test e-mail (task-check.ps1's mail step): Gmail's compose screen through a `mailto:` link to the device's
 * own Gmail address from the local configuration, with the subject "Kestrel test <marker>", the script's own tap on Send
 * only when Gmail is in front (else the setup fails), and Gmail's inbox, opened in a new task, until it shows the e-mail,
 * at most 2 minutes. When another app is in front of
 * the inbox at three polls in a row, such as Android's permission dialog, the setup fails at once with that app's
 * package. [GmailTrash], pushed before the e-mail exists, deletes it after the run (design D9 of sub-project 6). The
 * send, from the compose screen to the tap on Send, and the inbox's opening are changes ([dev.kestrel.eval.run.Restores.change]):
 * after Ctrl+C, the restore runs only once the send is over, and no inbox opens after it.
 */
data object MailToSelf : Setup {
    const val GMAIL = "com.google.android.gm"

    override fun apply(scope: RunScope): SetupResult {
        val address = scope.config.address(scope.device.kind)
            ?: return SetupResult.Untestable("corpus\\owner\\a71-config.json gives no address of this device's Gmail account")
        val marker = scope.values.marker
        GmailTrash.push(scope)
        val byHand = "Gmail: delete the conversation \"$marker\"."
        scope.restores.change("the test e-mail sent", byHand) {
            // once answers null for a failed command as for a lost answer, so Gmail must be in front before the tap: the hook
            // would tap the Send of whatever app is. A lost answer of the tap is read back by the inbox below.
            scope.device.once("am start -W -a android.intent.action.SENDTO -d 'mailto:$address?subject=${encode("Kestrel test $marker")}&body=${encode(marker)}' -p $GMAIL")
            scope.clock.sleep(2.seconds)
            if (Dumps.resumedPackage(scope.device.read("dumpsys activity activities")) != GMAIL) throw SetupFailed("Gmail's compose screen did not open.")
            val tapped = try {
                // Only while Gmail is in front, which the hook checks again on every screen that its tap acts on (plan 6B).
                scope.hooks.screenTap(label = "Send", packageName = GMAIL)["tapped"] == "true"
            } catch (e: HookLost) {
                true
            }
            if (!tapped) throw SetupFailed("Gmail's Send was not tapped.")
            GmailTrash.expect(scope)
        }
        // In a new task (NEW_TASK and CLEAR_TASK), because after a run Gmail's task still shows the e-mail that Kestrel
        // opened, and Android would only deliver the intent to it.
        scope.restores.change("Gmail's inbox opened", byHand) { scope.device.idempotent("am start -W -f 0x10008000 -n $GMAIL/.ConversationListActivityGmail") }
        val deadline = scope.clock.elapsed() + 2.minutes
        var elsewhere = 0
        while (true) {
            val found = scope.hooks.screenHas(packageName = GMAIL, contains = marker)
            if (found.int("count") >= 1) return SetupResult.Ready
            val app = found["app"]
            elsewhere = if (app != null && app != GMAIL) elsewhere + 1 else 0
            if (elsewhere >= 3) throw SetupFailed("Gmail's inbox did not show: $app is in front of it.")
            if (scope.clock.elapsed() > deadline) throw SetupFailed("The test e-mail did not reach the inbox within 2 minutes.")
            scope.clock.sleep(5.seconds)
        }
    }

    /** As PowerShell's `[uri]::EscapeDataString`, which writes a space as %20. */
    private fun encode(text: String): String = URLEncoder.encode(text, Charsets.UTF_8).replace("+", "%20")
}

/**
 * Gmail's own deletion of a test e-mail (design D9 of sub-project 6): the inbox opened in a new task, a long press on the
 * one conversation whose row holds the run's marker, and Delete, which moves it to Gmail's Trash, where Gmail deletes it
 * after 30 days; then a check that the inbox no longer shows the marker. On kestrel-api37 it then deletes up to three
 * other conversations whose rows hold "Kestrel test" and a word of the markers, the subject that only [MailToSelf]
 * writes, so that a test e-mail that an earlier run left goes too; on the A71 it touches only the run's own conversation. The labels are candidates
 * that Task 10 of plan 6B confirms on kestrel-api37 and Task 12 on the A71. On kestrel-api37 Gmail's row joins its
 * sender, subject and snippet with ", ", and its subject and snippet are elements of their own that hold the marker
 * too, so the first candidates name a part of the row that spans two of them: `screen-has` counts it only on the row,
 * and `screen-tap` finds it in the row's label. Delete is only the exact label: a part of a label could name a row,
 * which a tap in Gmail's selection mode would select too.
 *
 * A run pushes the deletion once. When the run's e-mail was sent ([expect]) but the inbox does not show it yet, the
 * deletion looks for it for up to a minute more, and says so when it never came.
 *
 * After the deletion, [discardDraft] looks at Gmail's Drafts, because a compose screen whose Send test mode blocked can
 * become a draft: on kestrel-api37 Gmail keeps the compose screen of email-self's dry run open in its own task without a
 * draft, and saves one when a changed compose screen is left. It discards only the draft whose row holds the run's
 * marker, by the inbox's row rules, and when it cannot, its restore fails with the by-hand step.
 */
object GmailTrash {
    const val SUBJECT = "Kestrel test"
    private val DELETE = listOf(listOf(TapCandidate(label = "Delete")))
    private val DRAWER = listOf(TapCandidate(label = "Open navigation drawer"))

    // The drawer's Drafts row, whose label is "Drafts" without a draft and "Drafts, 1" with one; its title is "Drafts".
    private val DRAFTS = listOf(TapCandidate(label = "Drafts", className = "LinearLayout"), TapCandidate(label = "Drafts"))
    private val DISCARD = listOf(TapCandidate(label = "Discard drafts"))
    private const val DISCARD_QUESTION = "Discard drafts from this conversation?"
    private val OK = listOf(TapCandidate(label = "OK"))
    private const val PUSHED = "gmail-trash"
    private const val SENT = "gmail-sent"
    private const val LOOKS = 12

    fun push(scope: RunScope) {
        val marker = scope.values.marker
        if (scope.notes[PUSHED] == marker) return
        scope.notes[PUSHED] = marker
        // Pushed first, so that it runs after the deletion from the inbox.
        scope.restores.push("the test draft in Gmail", "Gmail: in Drafts, discard the draft \"$marker\".") { discardDraft(scope, marker) }
        scope.restores.push("the test e-mail in Gmail", "Gmail: delete the conversation \"$marker\".") { delete(scope, marker) }
    }

    /** The run's e-mail is on its way, sent by the script or perhaps by Kestrel's task, so the deletion waits for it. */
    fun expect(scope: RunScope) {
        scope.notes[SENT] = scope.values.marker
    }

    fun delete(scope: RunScope, marker: String) {
        openInbox(scope)
        if (arrived(scope, marker)) {
            val row = row(marker)
            if (!AppTaps(scope, MailToSelf.GMAIL).run(listOf(row) + DELETE)) {
                // A lost answer ends the taps; the inbox in a new task drops a selection that they may have left.
                openInbox(scope)
                if (shows(scope, marker)) AppTaps(scope, MailToSelf.GMAIL).run(listOf(row) + DELETE)
            }
            check(!shows(scope, marker)) { "The test e-mail is still in Gmail's inbox." }
        } else if (scope.notes[SENT] == marker) {
            scope.console.warn("Gmail: the test e-mail \"$marker\" did not reach the inbox within a minute of the run's end, so it was not deleted. If it comes, delete it by hand.")
        }
        if (scope.device.kind == DeviceKind.EMULATOR) sweep(scope)
        scope.device.idempotent("input keyevent KEYCODE_HOME")
    }

    /**
     * Gmail's Drafts, opened from the inbox's navigation drawer: the draft whose row holds the run's marker is
     * long-pressed, discarded, and Gmail's question about it answered with OK; any other draft stays.
     */
    fun discardDraft(scope: RunScope, marker: String) {
        openInbox(scope)
        val taps = AppTaps(scope, MailToSelf.GMAIL)
        check(taps.run(listOf(DRAWER, DRAFTS))) { "Gmail's Drafts did not open." }
        if (shows(scope, marker)) {
            if (taps.run(listOf(row(marker), DISCARD)) && shows(scope, DISCARD_QUESTION)) taps.run(listOf(OK))
            check(!shows(scope, marker)) { "The test draft is still in Gmail's Drafts." }
        }
        scope.device.idempotent("input keyevent KEYCODE_HOME")
    }

    // MailToSelf's subject ends with the marker and its body is the marker; email-self's scripted model writes "Reminder about
    // {marker}" and "A reminder about {marker}.". A live model writes its own subject and body, which are elements of their
    // own beside the row, so the last candidate names the row as the one clickable element that holds the marker. Each
    // candidate is long-pressed only when it names one element.
    private fun row(marker: String): List<TapCandidate> =
        listOf("$marker, $marker", "$marker, A reminder about $marker", marker).map { TapCandidate(contains = it, long = true) } +
            TapCandidate(contains = marker, long = true, state = "clickable")

    fun openInbox(scope: RunScope) {
        // In a new task (NEW_TASK and CLEAR_TASK), because Gmail's task may still show an e-mail.
        scope.device.idempotent("am start -W -f 0x10008000 -n ${MailToSelf.GMAIL}/.ConversationListActivityGmail")
        scope.clock.sleep(3.seconds)
    }

    fun shows(scope: RunScope, text: String): Boolean = scope.hooks.screenHas(packageName = MailToSelf.GMAIL, contains = text).int("count") >= 1

    /** Whether the inbox shows [marker], at once or, when the run's e-mail was sent, within [LOOKS] looks 5 s apart. */
    private fun arrived(scope: RunScope, marker: String): Boolean {
        if (shows(scope, marker)) return true
        if (scope.notes[SENT] != marker) return false
        repeat(LOOKS) {
            scope.clock.sleep(5.seconds)
            if (shows(scope, marker)) return true
        }
        return false
    }

    /**
     * Only a row that holds "Kestrel test" and a word of the markers right after it, as [MailToSelf]'s subjects do, and
     * that the inbox shows once, is long-pressed, so that a real conversation about Kestrel's tests stays.
     */
    private fun sweep(scope: RunScope) {
        var deleted = 0
        for (word in Markers.WORDS) {
            if (deleted >= 3) return
            val subject = "$SUBJECT $word"
            // The sender of an e-mail to oneself is "me" in the row.
            val row = listOf("me, $subject", subject).map { TapCandidate(contains = it, long = true) }
            if (row.none { scope.hooks.screenHas(packageName = MailToSelf.GMAIL, contains = it.contains).int("count") == 1 }) continue
            // A new inbox drops the selection that an earlier word's taps may have left, so that Delete trashes only this row.
            openInbox(scope)
            if (AppTaps(scope, MailToSelf.GMAIL).run(listOf(row) + DELETE)) deleted++
        }
    }
}
