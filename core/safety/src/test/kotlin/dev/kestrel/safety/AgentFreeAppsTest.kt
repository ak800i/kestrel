package dev.kestrel.safety

import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.WindowKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design D16 of sub-project 5: the shipped list of r62 and r62b, the owner's changes and Play services' rule. */
class AgentFreeAppsTest {
    @Test
    fun theShippedListHoldsBothReportsWithoutPlayServicesAndWithSamsungWalletOnce() {
        val apps = AgentFreeApps()

        assertEquals(74, AgentFreeApps.SHIPPED.size)
        assertTrue(apps.isAgentFree("hr.asseco.android.intesa.isbd.bib"))
        assertTrue(apps.isAgentFree("com.x8bit.bitwarden"))
        assertTrue(apps.isAgentFree("com.samsung.knox.securefolder"))
        assertTrue(apps.isAgentFree("com.samsung.android.spay"))
        assertFalse(apps.isAgentFree("com.google.android.gms"))
        assertTrue(apps.isAgentFree("dev.kestrel"))
    }

    @Test
    fun namesCountOnlyWholeAndTheOwnersChangesAreKeptApart() {
        val apps = AgentFreeApps.from(AgentFreeChanges(added = listOf("com.example.bank"), removed = listOf("com.paypal.android.p2pmobile")))

        assertFalse(apps.isAgentFree("hr.asseco.android.jimba.mUCI.hu"))
        assertFalse(apps.isAgentFree("hr.asseco.android"))
        assertTrue(apps.isAgentFree("com.example.bank"))
        assertFalse(apps.isAgentFree("com.paypal.android.p2pmobile"))
        // Kestrel itself stays agent-free whatever the owner removes.
        assertTrue(AgentFreeApps(removed = setOf("dev.kestrel")).isAgentFree("dev.kestrel"))
        assertEquals("AgentFreeChanges(added=1, removed=1)", AgentFreeChanges(listOf("a"), listOf("b")).toString())
    }

    @Test
    fun launcherLabelsThatSuggestAnApp() {
        assertTrue(AgentFreeApps.suggests("mBanking"))
        assertTrue(AgentFreeApps.suggests("Moj novčanik"))
        assertTrue(AgentFreeApps.suggests("KeePassDX"))
        assertTrue(AgentFreeApps.suggests("2FA Codes"))
        assertFalse(AgentFreeApps.suggests("Telegram"))
    }

    @Test
    fun aPlayServicesWindowInFrontOrOverHalfTheScreenIsHandedOver() {
        val app = TestScreen()
        app.add(500, "Sign in")
        val small = TestScreen().apply { window(2, "com.google.android.gms", IntRect(0, 1700, 1000, 2000)) }
        val large = TestScreen().apply { window(2, "com.google.android.gms", IntRect(0, 600, 1000, 2000)) }
        val front = TestScreen().apply { window(2, "com.google.android.gms", IntRect(0, 1700, 1000, 2000), WindowKind.APPLICATION, active = true) }

        assertFalse(PlayServices.handsOver(app.snapshot()))
        assertFalse(PlayServices.handsOver(small.snapshot()))
        assertTrue(PlayServices.handsOver(large.snapshot()))
        assertTrue(PlayServices.handsOver(front.snapshot()))
    }
}
