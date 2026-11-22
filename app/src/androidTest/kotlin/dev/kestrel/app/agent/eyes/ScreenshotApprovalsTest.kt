package dev.kestrel.app.agent.eyes

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.KestrelApplication
import dev.kestrel.app.SettingKeys
import dev.kestrel.app.settings.StoredSettingsData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The stored screenshot approvals (design D20 of sub-project 5): an "always for this app" answer goes from the agent's
 * memory through AppGraph's seams into the encrypted setting, and the Safety page's "Revoke" takes it out again. The test
 * puts back the approvals that the device had.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotApprovalsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph = (context.applicationContext as KestrelApplication).graph

    @Test
    fun anApprovalOfTheAgentIsStoredPerAppAndProviderAndTheSafetyPageRevokesIt() = runBlocking {
        val before = graph.settings.get(SettingKeys.SCREENSHOT_APPROVALS)
        try {
            graph.settings.set(SettingKeys.SCREENSHOT_APPROVALS, ScreenshotApprovalList())
            graph.memory.approveScreenshots(APP, PROVIDER)
            graph.memory.approveScreenshots(APP, PROVIDER)
            graph.memory.approveScreenshots(APP, OTHER)

            assertTrue(graph.memory.screenshotApproved(APP, PROVIDER))
            assertFalse(graph.memory.screenshotApproved(APP, "no-such-provider"))
            assertFalse(graph.memory.screenshotApproved("org.example.other", PROVIDER))
            val both = listOf(ScreenshotApproval(APP, PROVIDER), ScreenshotApproval(APP, OTHER))
            assertEquals(both, graph.settings.get(SettingKeys.SCREENSHOT_APPROVALS).approvals)

            val data = StoredSettingsData(graph, context)
            val rows = data.screenshotApprovals.first()
            assertEquals(both, rows.map { it.approval })
            data.revokeScreenshots(rows.first { it.approval.providerId == PROVIDER })

            assertFalse(graph.memory.screenshotApproved(APP, PROVIDER))
            assertTrue(graph.memory.screenshotApproved(APP, OTHER))
            assertEquals(listOf(ScreenshotApproval(APP, OTHER)), data.screenshotApprovals.first().map { it.approval })
        } finally {
            graph.settings.set(SettingKeys.SCREENSHOT_APPROVALS, before)
        }
    }

    private companion object {
        const val APP = "dev.kestrel.testapp"
        const val PROVIDER = "github-copilot"
        const val OTHER = "other-provider"
    }
}
