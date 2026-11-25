package dev.kestrel.app.debug

import dev.kestrel.agent.AgentPorts
import dev.kestrel.agent.AgentSettings
import dev.kestrel.agent.testing.FakeApps
import dev.kestrel.agent.testing.FakeClock
import dev.kestrel.agent.testing.FakeData
import dev.kestrel.agent.testing.FakeEyes
import dev.kestrel.agent.testing.FakeLog
import dev.kestrel.agent.testing.FakeMemory
import dev.kestrel.agent.testing.FakeScreen
import dev.kestrel.agent.testing.ScreenBuilder
import dev.kestrel.agent.port.ModelPort
import dev.kestrel.agent.testing.ScriptedModel
import dev.kestrel.conversation.goal.RoundSetup
import dev.kestrel.conversation.goal.RoundStart
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.client.PricedResponse
import dev.kestrel.providers.profiles.ModelRole
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The A71 session's stand-in round (plan 2E's voice-task; design 14.4 of sub-project 7): every round of a goal runs on
 * fake ports, on a launcher's screen that the round only reads, and its model waits [seconds] and then gives up with [reply].
 * So the goal's final reply is "I couldn't do it: <reply>. I didn't change anything.", and the owner can interrupt it,
 * stop it or say a new command while it waits, with nothing on the phone touched and no provider called.
 */
internal class StandInRound(private val seconds: Int, private val reply: String) {
    fun prepare(start: RoundStart): RoundSetup {
        val failing = ScriptedModel(
            listOf(buildJsonObject {
                put("tool", "fail")
                put("arguments", buildJsonObject { put("reason", reply) })
            }),
        )
        val ports = AgentPorts(
            FakeScreen(ScreenBuilder.screen(LAUNCHER) { text("Home", top = 100) }),
            FakeEyes(),
            FakeData(),
            start.owner,
            Waiting(failing, seconds * 1_000L),
            FakeApps(),
            FakeMemory(),
            FakeLog(),
            FakeClock(),
            start.goal,
            start.history,
        )
        return RoundSetup.Ready(ports, AgentSettings(), "task-${start.command}-standin", emptySet(), emptyFlow(), offersRoutines = false) {}
    }

    override fun toString(): String = "StandInRound(seconds=$seconds, reply=${reply.length} characters)"

    // The scripted model, which waits before each reply as a slow provider would.
    private class Waiting(private val inner: ModelPort, private val millis: Long) : ModelPort by inner {
        override suspend fun call(role: ModelRole, request: ChatRequest, step: Int): PricedResponse {
            delay(millis)
            return inner.call(role, request, step)
        }
    }

    private companion object {
        const val LAUNCHER = "com.android.launcher3"
    }
}
