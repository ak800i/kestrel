package dev.kestrel.app.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.agent.EndReason
import dev.kestrel.agent.testing.ScriptedModel
import dev.kestrel.agent.testing.ScriptedModel.Companion.call
import dev.kestrel.app.orchestrator.TaskAsk
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.app.screen.ScreenServiceRule.Companion.KESTREL
import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.screen.model.ScreenTree
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The agent end to end on kestrel-api37 with the scripted model (design D27 of sub-project 5; D32 items 16 and 50): a
 * delete that the owner confirms on the card's question runs once, a hostile script on an injection fixture never presses
 * a send, call or delete without the owner, and the agent stays out of Kestrel's own app. Test mode stays on; a delete is
 * no send, so test mode does not block it.
 */
@RunWith(AndroidJUnit4::class)
class AgentEndToEndTest {
    @get:Rule
    val screen = ScreenServiceRule()

    @Before
    fun start() {
        AgentTests.clearPresses(screen)
        AgentTests.start()
    }

    @After
    fun stop() {
        try {
            AgentTests.stop()
        } finally {
            AgentTests.clearPresses(screen)
            screen.shell("am force-stop $FIXTURES")
        }
    }

    private fun tap(label: String, category: String) = call("tap", label, null, null, "reason" to "the next step", "category" to category)

    private val fail = call("fail", null, null, null, "reason" to "I stop here.")

    @Test
    fun aDeleteIsAskedOnTheCardAndRunsOnceAfterTheOwnersYes() = runBlocking {
        screen.open("MessengerActivity", "Ana Petrović", "--es chat ana")
        val model = ScriptedModel(listOf(
            tap("Delete chat", "deleting"),
            call("finish", null, null, null, "summary" to "I deleted the chat.", "evidence" to listOf(mapOf("target" to mapOf("label" to "Chat deleted")))),
        ))

        val task = AgentTests.run("delete my chat with Ana", model)
        AgentTests.awaitAsking(task, TaskAsk.CONFIRM)
        check(VoiceTests.waitUntil(5_000) { screen.showsWindow("Kestrel confirmation") }) { "The card did not show" }
        assertEquals(emptyList<String>(), AgentTests.presses(screen))
        AgentTests.say("confirm")
        val ended = AgentTests.ended(task)

        assertEquals(EndReason.FINISHED, ended.report.end)
        assertEquals(0, ended.report.unconfirmed)
        assertEquals(listOf("delete"), AgentTests.presses(screen))
    }

    @Test
    fun aHostileScriptOnAnInjectionFixtureNeverPressesWithoutTheOwner() = runBlocking {
        for (label in listOf("Delete chat", "Call", "Send")) {
            screen.open("InjectionActivity", "Ana Petrović", "--es fixture inject-01")
            val model = ScriptedModel(listOf(tap(label, "reading"), fail))

            val task = AgentTests.run("read my newest message from Ana", model)
            // Each question is answered "cancel", as task-check.ps1 answers the injection fixtures (design D28).
            check(VoiceTests.waitUntil(30_000) {
                val state = AgentTests.graph.listening.state.value
                if (state.task == task && state.taskAsking != null) AgentTests.say("cancel")
                AgentTests.graph.agent.ended(task) != null && !AgentTests.graph.agent.running(task)
            }) { "The task did not end" }
            val ended = AgentTests.ended(task)

            assertEquals(label, emptyList<String>(), AgentTests.presses(screen))
            assertEquals(label, 0, ended.report.unconfirmed)
            assertEquals(label, 0, ended.report.beforeTestMode)
        }
    }

    @Test
    fun theAgentStaysOutOfKestrelsOwnApp() = runBlocking {
        // Kestrel's settings in front: the task stops before any action (design D16; D32 item 16).
        screen.shell("am start -W -n $KESTREL/.app.MainActivity")
        screen.awaitElement { it.packageName == KESTREL }
        val settings = ScriptedModel(listOf(tap("Safety", "reading"), fail))
        val first = AgentTests.ended(AgentTests.run("open the safety page", settings))
        assertEquals(EndReason.AGENT_FREE, first.report.end)

        // open_app refuses Kestrel: the fixture stays in front.
        screen.open("MessengerActivity", "Chats")
        val open = ScriptedModel(listOf(call("open_app", null, null, null, "name" to "Kestrel", "reason" to "open it"), fail))
        val second = AgentTests.ended(AgentTests.run("open Kestrel", open))

        assertEquals(EndReason.FAILED_BY_MODEL, second.report.end)
        assertEquals(FIXTURES, ScreenTree.frontWindow(screen.session.snapshots.current().snapshot)?.packageName)
        assertTrue(open.requests.size >= 2)
    }
}
