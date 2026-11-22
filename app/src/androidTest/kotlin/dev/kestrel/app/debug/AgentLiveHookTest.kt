package dev.kestrel.app.debug

import android.content.Intent
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
 * with live=true may call the owner's provider. The test puts back the setting that the device had.
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

    private suspend fun hook(on: Boolean?, model: String? = null): String {
        val intent = Intent(TestHooks.ACTION_PREFIX + "AGENT_LIVE")
        if (on != null) intent.putExtra("on", on)
        if (model != null) intent.putExtra("model", model)
        return TestHooks(graph, context).run(intent).text
    }
}
