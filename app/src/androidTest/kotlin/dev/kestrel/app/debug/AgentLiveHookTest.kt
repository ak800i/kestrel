package dev.kestrel.app.debug

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.KestrelApplication
import dev.kestrel.app.SettingKeys
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hook agent-live of plan 5C (design D28): it opens and closes the live session of sitting 2, in which a task sent
 * with live=true may call the owner's provider; and agent-approvals, with which sitting 2 revokes the screenshot approvals
 * that it stored. The tests put back the settings that the device had.
 */
class AgentLiveHookTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph = (context.applicationContext as KestrelApplication).graph

    @Test
    fun agentLiveOpensAndClosesTheLiveSession() = runBlocking {
        val before = graph.settings.get(SettingKeys.AGENT_LIVE_SESSION)
        try {
            assertEquals("live=on", hook(true))
            assertTrue(graph.settings.get(SettingKeys.AGENT_LIVE_SESSION))
            // Without -On it only answers.
            assertEquals("live=on", hook(null))
            assertEquals("live=off", hook(false))
            assertFalse(graph.settings.get(SettingKeys.AGENT_LIVE_SESSION))
            // With a model it also says whether the default profile's own list holds it; no list holds this one.
            assertEquals("live=off model=unlisted", hook(null, "no-such-model"))
        } finally {
            graph.settings.set(SettingKeys.AGENT_LIVE_SESSION, before)
        }
    }

    @Test
    fun agentApprovalsRevokesOnlyTheNamedAppsApprovals() = runBlocking {
        val before = graph.settings.get(SettingKeys.SCREENSHOT_APPROVALS)
        try {
            graph.settings.set(SettingKeys.SCREENSHOT_APPROVALS, dev.kestrel.app.agent.eyes.ScreenshotApprovalList())
            graph.screenshotApprovals.approve("dev.kestrel.testapp", "copilot")
            graph.screenshotApprovals.approve("dev.kestrel.testapp", "other")
            graph.screenshotApprovals.approve("org.example.kept", "copilot")
            // Without a package it only counts.
            assertEquals("approvals=3 revoked=0", approvals(null))
            assertEquals("approvals=1 revoked=2", approvals("dev.kestrel.testapp"))
            assertTrue(graph.screenshotApprovals.approved("org.example.kept", "copilot"))
            assertEquals("approvals=1 revoked=0", approvals("dev.kestrel.testapp"))
        } finally {
            graph.settings.set(SettingKeys.SCREENSHOT_APPROVALS, before)
        }
    }

    private suspend fun approvals(revoke: String?): String {
        val arguments = Bundle()
        if (revoke != null) arguments.putString("revoke", revoke)
        return TestHooks(graph, context).run("AGENT_APPROVALS", arguments).text
    }

    private suspend fun hook(on: Boolean?, model: String? = null): String {
        val arguments = Bundle()
        if (on != null) arguments.putBoolean("on", on)
        if (model != null) arguments.putString("model", model)
        return TestHooks(graph, context).run("AGENT_LIVE", arguments).text
    }
}
