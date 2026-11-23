package dev.kestrel.eval.run

import dev.kestrel.eval.device.Device
import dev.kestrel.eval.device.Dumps
import dev.kestrel.eval.hook.HookError
import dev.kestrel.eval.hook.HookSilent
import dev.kestrel.eval.hook.Hooks

/** kestrel-api37's Google accounts or its default profile changed during a command. */
class AccountsChanged(message: String) : Exception(message)

/**
 * kestrel-api37 holds the owner's own Google account and Kestrel's Copilot profile, which no step may sign in, remove or
 * clear (AGENTS.md; design D1 of sub-project 6). Before and after each command on it, the tool reads how many Google
 * accounts `dumpsys account` counts and the default profile's kind (hook provider-standin), and stops with an error when
 * either changed. It reads the type of each account only, never a name.
 */
class AccountGuard(private val device: Device, private val hooks: Hooks) {
    data class Snapshot(val googleAccounts: Int, val profile: String?)

    /** The count of Google accounts, and the default profile's kind, or null when Kestrel does not answer, as before its install. */
    fun read(): Snapshot {
        val profile = try {
            hooks.providerStandin()["profile"]
        } catch (e: HookSilent) {
            null
        } catch (e: HookError) {
            null
        }
        return Snapshot(Dumps.accountCount(device.read("dumpsys account"), "com.google"), profile)
    }

    /** Stops with an error when the count changed, or the profile changed from a kind that the first read gave. */
    fun check(before: Snapshot) {
        val now = read()
        if (now.googleAccounts != before.googleAccounts || (before.profile != null && now.profile != before.profile)) {
            throw AccountsChanged(
                "kestrel-api37's Google accounts (${before.googleAccounts} before, ${now.googleAccounts} now) or its default profile " +
                    "(${before.profile} before, ${now.profile} now) changed during the command. Check the emulator by hand.",
            )
        }
    }
}
