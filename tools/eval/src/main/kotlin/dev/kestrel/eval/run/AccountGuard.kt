package dev.kestrel.eval.run

import dev.kestrel.eval.device.Device
import dev.kestrel.eval.device.Dumps
import dev.kestrel.eval.hook.HookError
import dev.kestrel.eval.hook.HookSilent
import dev.kestrel.eval.hook.Hooks
import dev.kestrel.eval.hook.ProviderState

/** kestrel-api37's Google accounts or Kestrel's profiles changed during a command. */
class AccountsChanged(message: String) : Exception(message)

/**
 * kestrel-api37 holds the owner's own Google account and Kestrel's Copilot profile, which no step may sign in, remove,
 * replace or clear (AGENTS.md; design D1 of sub-project 6). Before and after each command on it, the tool reads how many
 * Google accounts `dumpsys account` counts and what the hook provider-standin answers about the profiles ([ProviderState]:
 * the default profile's kind, ID and credential state, and the number of Copilot profiles), and stops with an error that
 * names each change ([changes]). scripts\lib\DeviceGuard.ps1 compares the same fields in the same way
 * (Get-OwnerDataChange). It reads the type of each account only, never a name.
 */
class AccountGuard(private val device: Device, private val hooks: Hooks) {
    /** The count of Google accounts, and Kestrel's profiles, or null when Kestrel does not answer, as before its install. */
    data class Snapshot(val googleAccounts: Int, val providers: ProviderState?)

    /** Reads the snapshot; an older build whose answer lacks a field of [ProviderState] is refused. */
    fun read(): Snapshot {
        val providers = try {
            hooks.providerStandin()
        } catch (e: HookSilent) {
            null
        } catch (e: HookError) {
            null
        }
        return Snapshot(Dumps.accountCount(device.read("dumpsys account"), "com.google"), providers)
    }

    /** Stops with an error that names each change since [before]. */
    fun check(before: Snapshot) {
        val changes = changes(before, read())
        if (changes.isNotEmpty()) {
            throw AccountsChanged("kestrel-api37's owner data changed during the command: ${changes.joinToString("; ")}. Check the emulator by hand.")
        }
    }

    companion object {
        /**
         * Each change between two snapshots. Profiles that Kestrel did not answer about before are not compared; profiles
         * that it answered about before and not after count as a change, because they may be gone.
         */
        fun changes(before: Snapshot, now: Snapshot): List<String> = buildList {
            if (now.googleAccounts != before.googleAccounts) add("Google accounts ${before.googleAccounts} before, ${now.googleAccounts} now")
            val then = before.providers ?: return@buildList
            val after = now.providers
            if (after == null) {
                add("Kestrel's profiles could not be read afterwards, so its Copilot profile may be gone")
                return@buildList
            }
            if (after.profile != then.profile) add("default profile ${then.profile} before, ${after.profile} now")
            if (after.copilot != then.copilot) add("Copilot profiles ${then.copilot} before, ${after.copilot} now")
            if (after.id != then.id) add("the default profile was replaced (${then.id} before, ${after.id} now)")
            if (after.credential != then.credential) add("the default profile's credential ${then.credential} before, ${after.credential} now")
        }
    }
}
