package dev.kestrel.app.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.agent.EndReason
import dev.kestrel.agent.testing.ScriptedModel
import dev.kestrel.agent.testing.ScriptedModel.Companion.call
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.model.GoalPhase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A goal's pause on a lock on kestrel-api37 (design 6.5 and 14.3 of sub-project 7): the screen turning off pauses the goal
 * through PhoneSignals and ConversationSignals, also while listening is off, and the goal waits at its next step until the
 * owner says yes to "Continue the goal?" after the unlock. The model's replies wait until the test lets each one go, so no
 * step depends on the emulator's speed. kestrel-api37 has no lock screen, so waking the screen unlocks it; the test
 * changes no setting of the lock screen.
 */
@RunWith(AndroidJUnit4::class)
class GoalPauseTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val conversations = AgentTests.graph.conversations

    // A say passes the agent's gate at the next turn, where a paused goal waits; fail ends the goal.
    private val onIt = call("say", null, null, null, "text" to "On it.")
    private val fail = call("fail", null, null, null, "reason" to "I stop here.")

    @Before
    fun start() {
        VoiceTests.assumeEmulator()
        AgentTests.start()
    }

    @After
    fun stop() {
        wake()
        AgentTests.stop()
    }

    @Test
    fun aLockPausesTheGoalUntilTheOwnersYesAfterTheUnlock() {
        val scripted = ScriptedModel(listOf(onIt, fail))
        val model = HeldModel(scripted)
        AgentTests.run("read my newest notification", model)
        awaitCalls(model, 1)

        lockAndHold(model)
        wake()
        val listening = AgentTests.graph.listening
        check(VoiceTests.waitUntil(30_000) {
            val heard = listening.state.value
            conversations.view.value.asking == QuestionKind.CONTINUE_GOAL && heard.screenOn && heard.openQuestion?.kind == QuestionKind.CONTINUE_GOAL
        }) { "No \"Continue the goal?\" after the unlock" }
        assertEquals(GoalPhase.PAUSED, conversations.view.value.goal)
        assertEquals(1, model.calls)

        AgentTests.say("yes")
        // The goal goes on from its next step: only now does the model get its second request.
        awaitCalls(model, 2)
        model.release()
        check(VoiceTests.waitUntil(30_000) { conversations.view.value.goal == null }) { "The goal did not end" }
        assertEquals(EndReason.FAILED_BY_MODEL, conversations.outcome(conversations.view.value.command)?.report?.end)
        assertEquals(2, scripted.requests.size)
    }

    @Test
    fun aLockPausesTheGoalAlsoWhileListeningIsOff() {
        val model = HeldModel(ScriptedModel(listOf(onIt, fail)))
        AgentTests.run("read my newest notification", model)
        awaitCalls(model, 1)
        AgentTests.turnOff()
        check(VoiceTests.waitUntil(10_000) { !VoiceTests.listeningServiceRuns() }) { "ListeningService still runs" }

        lockAndHold(model)
        wake()
        // Listening comes back, so that stop() can end the goal by voice.
        AgentTests.start()
    }

    // The lock comes while the model's first reply waits. Once that reply goes, the goal stays paused at its next step and
    // asks the model nothing more.
    private fun lockAndHold(model: HeldModel) {
        screen.shell("input keyevent KEYCODE_SLEEP")
        check(VoiceTests.waitUntil(15_000) { conversations.view.value.goal == GoalPhase.PAUSED }) { "The lock did not pause the goal" }
        model.release()
        Thread.sleep(PAUSED_MILLIS)
        assertEquals(GoalPhase.PAUSED, conversations.view.value.goal)
        assertEquals(1, model.calls)
    }

    private fun awaitCalls(model: HeldModel, calls: Int) {
        check(VoiceTests.waitUntil(30_000) { model.calls == calls }) { "The model did not get request $calls" }
    }

    private fun wake() {
        screen.shell("input keyevent KEYCODE_WAKEUP")
        screen.shell("wm dismiss-keyguard")
    }

    private companion object {
        // How long the test watches a paused goal stay paused.
        const val PAUSED_MILLIS = 3_000L
    }
}
