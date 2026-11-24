package dev.kestrel.eval.run

import dev.kestrel.eval.device.Refusal
import dev.kestrel.eval.task.FakeRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The guard of kestrel-api37's owner data (AGENTS.md; design D1 of sub-project 6): it catches a profile that another one
 * replaced and a credential that was deleted, which leave the profile's kind and the count of Copilot profiles as they
 * were, and it refuses an older build whose hook does not answer the profile's ID and credential.
 */
class AccountGuardTest {
    private val run = FakeRun()
    private var standIn = "standin=off profile=copilot copilot=1 id=profile-1 credential=present"

    init {
        run.adb.on("^dumpsys account$") { "    Account {name=pera@example.com, type=com.google}" }
        run.adb.hook("PROVIDER_STANDIN") { standIn }
    }

    private val guard = AccountGuard(run.device, run.hooks)

    @Test
    fun nothingChangedPasses() {
        guard.check(guard.read())
    }

    @Test
    fun aCopilotProfileThatAnotherOneReplacedStopsTheCommand() {
        val before = guard.read()
        standIn = "standin=off profile=copilot copilot=1 id=profile-2 credential=present"

        val changed = assertThrows(AccountsChanged::class.java) { guard.check(before) }

        assertEquals(
            "kestrel-api37's owner data changed during the command: the default profile was replaced (profile-1 before, profile-2 now). " +
                "Check the emulator by hand.",
            changed.message,
        )
    }

    @Test
    fun aDeletedCredentialStopsTheCommand() {
        val before = guard.read()
        standIn = "standin=off profile=copilot copilot=1 id=profile-1 credential=missing"

        val changed = assertThrows(AccountsChanged::class.java) { guard.check(before) }

        assertEquals(
            "kestrel-api37's owner data changed during the command: the default profile's credential present before, missing now. " +
                "Check the emulator by hand.",
            changed.message,
        )
    }

    @Test
    fun aRemovedProfileNamesEachChange() {
        val before = guard.read()
        standIn = "standin=off profile=none copilot=0 id=none credential=none"

        assertEquals(
            listOf(
                "default profile copilot before, none now",
                "Copilot profiles 1 before, 0 now",
                "the default profile was replaced (profile-1 before, none now)",
                "the default profile's credential present before, none now",
            ),
            AccountGuard.changes(before, guard.read()),
        )
    }

    @Test
    fun anOlderBuildWithoutTheProfilesIdAndCredentialIsRefused() {
        standIn = "standin=off profile=copilot copilot=1"

        val refused = assertThrows(Refusal::class.java) { guard.read() }

        assertEquals(
            "Not an answer of provider-standin: 'standin=off profile=copilot copilot=1'. Install the current debug build with scripts\\install.ps1.",
            refused.message,
        )
    }
}
