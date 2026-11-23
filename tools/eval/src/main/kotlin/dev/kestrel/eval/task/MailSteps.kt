package dev.kestrel.eval.task

import dev.kestrel.eval.device.Dumps
import dev.kestrel.eval.hook.HookLost
import java.net.URLEncoder
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * read-email's test e-mail (task-check.ps1's mail step): Gmail's compose screen through a `mailto:` link to the device's
 * own Gmail address from the local configuration, with the subject "Kestrel test <marker>", the script's own tap on Send
 * only when Gmail is in front (else the setup fails), and Gmail's inbox, opened in a new task, until it shows the e-mail,
 * at most 2 minutes. When another app is in front of
 * the inbox at three polls in a row, such as Android's permission dialog, the setup fails at once with that app's
 * package. Plan 6B adds the e-mail's deletion after the run (design D9 of sub-project 6).
 */
data object MailToSelf : Setup {
    const val GMAIL = "com.google.android.gm"

    override fun apply(scope: RunScope): SetupResult {
        val address = scope.config.address(scope.device.kind)
            ?: return SetupResult.Untestable("corpus\\owner\\a71-config.json gives no address of this device's Gmail account")
        val marker = scope.values.marker
        // once answers null for a failed command as for a lost answer, so Gmail must be in front before the tap: the hook
        // would tap the Send of whatever app is. A lost answer of the tap is read back by the inbox below.
        scope.device.once("am start -W -a android.intent.action.SENDTO -d 'mailto:$address?subject=${encode("Kestrel test $marker")}&body=${encode(marker)}' -p $GMAIL")
        scope.clock.sleep(2.seconds)
        if (Dumps.resumedPackage(scope.device.read("dumpsys activity activities")) != GMAIL) throw SetupFailed("Gmail's compose screen did not open.")
        val tapped = try {
            scope.hooks.screenTap("Send")["tapped"] == "true"
        } catch (e: HookLost) {
            true
        }
        if (!tapped) throw SetupFailed("Gmail's Send was not tapped.")
        // In a new task (NEW_TASK and CLEAR_TASK), because after a run Gmail's task still shows the e-mail that Kestrel
        // opened, and Android would only deliver the intent to it.
        scope.device.idempotent("am start -W -f 0x10008000 -n $GMAIL/.ConversationListActivityGmail")
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
