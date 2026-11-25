package dev.kestrel.app.debug

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.agent.testing.ScriptedModel
import dev.kestrel.agent.testing.ScriptedModel.Companion.call
import dev.kestrel.app.agent.AgentTests
import dev.kestrel.app.agent.HeldModel
import dev.kestrel.app.goal.NoticeKind
import dev.kestrel.app.goal.ServiceState
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.conversation.model.GoalPhase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The goal service's hooks on kestrel-api37 (plan 7E): goal-service-state in kinds and counts, the audio-focus request's
 * answer, and goal-control's press of the held goal's Continue in SystemUI's shade, which resumes the goal as the owner's
 * tap would. The focus that the instrumented process gets proves nothing about Android's hardening; this checks only that
 * the hook answers, and Task 8 runs it outside the instrumentation. `AgentTests.stop` ends whatever "stop" stops.
 */
@RunWith(AndroidJUnit4::class)
class GoalServiceHooksTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph get() = AgentTests.graph
    private val conversations get() = graph.conversations
    private val onIt = call("say", null, null, null, "text" to "On it.")
    private val fail = call("fail", null, null, null, "reason" to "I stop here.")

    @Before
    fun start() {
        VoiceTests.assumeEmulator()
        AgentTests.start()
    }

    @After
    fun stop() {
        screen.shell("cmd statusbar collapse")
        AgentTests.stop()
    }

    @Test
    fun goalServiceStateAnswersInKindsAndCountsWhileAGoalWorks() {
        val model = HeldModel(ScriptedModel(listOf(fail)))
        AgentTests.run("read my newest notification", model)
        check(VoiceTests.waitUntil(30_000) { model.calls == 1 && graph.goalForeground.status().service == ServiceState.RUNNING }) { "No goal service" }

        val answer = hook("GOAL_SERVICE_STATE")

        assertTrue(
            answer,
            Regex(
                "service=running notice=working posted=none actions=stop,open progress=none types=special-use applied=special-use " +
                    "android=running androidTypes=special-use starts=\\d+ refusals=0 focus=(none|granted|failed) focusBy=(none|speak|focus) " +
                    "focusTypes=\\S+",
            ).matches(answer),
        )
        assertEquals("speak=sent", hook("GOAL_CONTROL", "do" to "speak"))
        check(VoiceTests.waitUntil(10_000) { hook("GOAL_SERVICE_STATE").contains("focusBy=speak focusTypes=special-use+media-playback") }) {
            "The speak request did not answer with the speech's types"
        }
        assertEquals("error: add --es do with one of tap, touch, speak, focus, refuse or send-later", hook("GOAL_CONTROL", "do" to "other"))
        assertEquals("error: add --es action with one of stop, open, continue or cancel", hook("GOAL_CONTROL", "do" to "tap"))
        model.release()
    }

    @Test
    fun goalControlPressesTheHeldGoalsContinueInTheShadeAndTheGoalGoesOn() {
        val model = HeldModel(ScriptedModel(listOf(onIt, fail)))
        AgentTests.run("read my newest notification", model)
        check(VoiceTests.waitUntil(30_000) { model.calls == 1 && graph.goalForeground.status().service == ServiceState.RUNNING }) {
            "The model got no request, or the goal service does not run"
        }

        assertEquals("touch=sent", hook("GOAL_CONTROL", "do" to "touch"))
        check(VoiceTests.waitUntil(15_000) {
            conversations.view.value.goal == GoalPhase.INTERRUPTED && graph.goalForeground.notice()?.kind == NoticeKind.HELD
        }) { "The touch did not hold the goal" }
        val starts = graph.goalForeground.status().starts

        assertEquals("tapped=continue", hook("GOAL_CONTROL", "do" to "tap", "action" to "continue"))

        check(VoiceTests.waitUntil(30_000) { model.calls == 2 }) { "The goal did not go on" }
        check(VoiceTests.waitUntil(15_000) { graph.goalForeground.status().starts == starts + 1 }) { "The goal service was not started again" }
        model.release()
        check(VoiceTests.waitUntil(30_000) { model.calls == 3 }) { "The goal took no next step" }
        model.release()
        check(VoiceTests.waitUntil(30_000) { conversations.view.value.goal == null }) { "The goal did not end" }
    }

    private fun hook(command: String, vararg extras: Pair<String, String>): String {
        val intent = Intent(TestHooks.ACTION_PREFIX + command)
        for ((key, value) in extras) intent.putExtra(key, value)
        return runBlocking { TestHooks(graph, context).run(intent) }.text
    }
}
