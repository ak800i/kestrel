package dev.kestrel.eval.task

import dev.kestrel.eval.device.Dumps
import kotlin.time.Duration.Companion.seconds

/**
 * The run's first name `{name}` (design D4 of sub-project 6): the command's `--name` when it has one; otherwise
 * [default], and for a Telegram task the first of [default], Mira, Zora, Ivo and Teo that the owner's Telegram has no chat
 * with, as a71-agent.ps1's `Select-TestName` chose it, so that the test alias never names one of his chats. Telegram is
 * opened to count the rows that hold each name, on the phone, and stopped again.
 */
data class TestName(val default: String, val telegram: Boolean) : Setup {
    override fun apply(scope: RunScope): SetupResult {
        if (scope.values.name != null) return SetupResult.Ready
        if (!telegram) {
            scope.values.name = default
            return SetupResult.Ready
        }
        val app = TELEGRAMS.firstOrNull { Dumps.packageListed(scope.device.read("pm list packages $it"), it) }
            ?: return SetupResult.Untestable("Telegram is not installed")
        scope.restores.push("Telegram stopped", "Close Telegram.") { scope.device.idempotent("am force-stop $app") }
        scope.restores.change("Telegram opened", "Close Telegram.") { scope.device.idempotent("monkey -p $app -c android.intent.category.LAUNCHER 1") }
        scope.clock.sleep(4.seconds)
        val free = (listOf(default) + OTHERS).firstOrNull { scope.hooks.screenHas(packageName = app, contains = it).int("count") == 0 }
        scope.device.idempotent("am force-stop $app")
        scope.values.name = free ?: return SetupResult.Untestable("every test name has a chat in the owner's Telegram")
        return SetupResult.Ready
    }

    companion object {
        /** Telegram's website build, which the owner's A71 has, and Play's build. */
        val TELEGRAMS = listOf("org.telegram.messenger.web", "org.telegram.messenger")
        val OTHERS = listOf("Mira", "Zora", "Ivo", "Teo")
    }
}
