package dev.kestrel.app.settings

import dev.kestrel.agent.port.LauncherApp
import dev.kestrel.safety.AgentFreeApps
import dev.kestrel.safety.AgentFreeChanges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSafetyEditsTest {
    private val shipped = AgentFreeApps.SHIPPED.first()

    @Test
    fun anAddedAppIsRecordedOnceAndAShippedAppThatWasRemovedComesBack() {
        val added = AgentFreeEdits.add(AgentFreeChanges(), "org.example.wallet")

        assertEquals(listOf("org.example.wallet"), added.added)
        assertEquals(added, AgentFreeEdits.add(added, "org.example.wallet"))
        val removed = AgentFreeChanges(removed = listOf(shipped))
        assertEquals(AgentFreeChanges(), AgentFreeEdits.add(removed, shipped))
        assertEquals(AgentFreeChanges(), AgentFreeEdits.add(AgentFreeChanges(), shipped))
    }

    @Test
    fun removingAnAddedAppForgetsItAndRemovingAShippedAppRecordsIt() {
        val changes = AgentFreeChanges(added = listOf("org.example.wallet"))

        assertEquals(AgentFreeChanges(), AgentFreeEdits.remove(changes, "org.example.wallet"))
        val removed = AgentFreeEdits.remove(AgentFreeChanges(), shipped)
        assertEquals(listOf(shipped), removed.removed)
        assertFalse(shipped in AgentFreeEdits.listed(removed))
        assertEquals(removed, AgentFreeEdits.remove(removed, shipped))
    }

    @Test
    fun kestrelIsNeverOnTheEditableList() {
        assertEquals(AgentFreeChanges(), AgentFreeEdits.add(AgentFreeChanges(), AgentFreeApps.KESTREL))
        assertEquals(AgentFreeChanges(), AgentFreeEdits.remove(AgentFreeChanges(), AgentFreeApps.KESTREL))
        assertFalse(AgentFreeApps.KESTREL in AgentFreeEdits.listed(AgentFreeChanges()))
        assertTrue(AgentFreeApps.from(AgentFreeEdits.remove(AgentFreeChanges(), AgentFreeApps.KESTREL)).isAgentFree(AgentFreeApps.KESTREL))
    }

    @Test
    fun theSuggestionsAreInstalledAppsThatSoundLikeMoneyAndAreNotAgentFreeYet() {
        val bank = LauncherApp("Moja Banka", "rs.example.mojabanka", null)
        val apps = listOf(bank, LauncherApp("Notes", "org.example.notes", null), bank)

        assertEquals(listOf(bank), AgentFreeEdits.suggestions(AgentFreeChanges(), apps))
        assertEquals(emptyList<LauncherApp>(), AgentFreeEdits.suggestions(AgentFreeEdits.add(AgentFreeChanges(), bank.packageName), apps))
    }

    @Test
    fun theOwnersAddressesAreSplitAtCommasSemicolonsAndSpaces() {
        assertEquals(listOf("a@example.com", "b@example.com", "c@example.com"), ownerEmails(" a@example.com, b@example.com;c@example.com "))
        assertEquals(emptyList<String>(), ownerEmails(" , "))
    }

    @Test
    fun aBedrockApplicationInferenceProfileGetsTheNote() {
        val arn = "arn:aws:bedrock:eu-west-1:123456789012:application-inference-profile/abc123"

        assertTrue(bedrockApplicationProfile("amazon-bedrock", listOf("anthropic.claude-sonnet-5-v1:0", arn)))
        assertFalse(bedrockApplicationProfile("amazon-bedrock", listOf("anthropic.claude-sonnet-5-v1:0")))
        assertFalse(bedrockApplicationProfile("anthropic", listOf(arn)))
    }
}
