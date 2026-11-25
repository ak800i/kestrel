package dev.kestrel.app.debug

import dev.kestrel.agent.AgentPorts
import dev.kestrel.agent.AgentSettings
import dev.kestrel.agent.EndReason
import dev.kestrel.agent.TaskReport
import dev.kestrel.agent.port.ModelPort
import dev.kestrel.agent.testing.FakeApps
import dev.kestrel.agent.testing.FakeClock
import dev.kestrel.agent.testing.FakeData
import dev.kestrel.agent.testing.FakeEyes
import dev.kestrel.agent.testing.FakeLog
import dev.kestrel.agent.testing.FakeMemory
import dev.kestrel.agent.testing.FakeScreen
import dev.kestrel.agent.testing.ScreenBuilder
import dev.kestrel.agent.testing.ScriptedModel
import dev.kestrel.agent.tools.Tools
import dev.kestrel.app.agent.AgentTexts
import dev.kestrel.conversation.goal.GoalEnvironment
import dev.kestrel.conversation.goal.RoundSetup
import dev.kestrel.conversation.goal.RoundStart
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.client.PricedResponse
import dev.kestrel.providers.profiles.ModelRole
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow

/**
 * The A71 session's stand-in for the phone's rounds (plan 2E's voice-task; design 14.4 of sub-project 7): every round of
 * a goal runs on fake ports, on a launcher's screen that the round only reads, and its model waits [seconds] and then
 * finishes, citing the screen's "Home". The finished round's final words are [reply] itself, whole and of any length, as
 * the answer to the owner's goal; the model's own summary and a failure's reason have limits, so the reply never goes
 * through them. So the owner can interrupt the reply, stop the goal or say a new command while it waits, with nothing on
 * the phone touched and no provider called. Any other end, such as a stop, keeps Kestrel's own words for it.
 */
internal class StandInRound(private val seconds: Int, private val reply: String) : GoalEnvironment {
    override suspend fun prepare(start: RoundStart): RoundSetup {
        val finishing = ScriptedModel(
            listOf(ScriptedModel.call(Tools.FINISH, null, null, null, "summary" to SUMMARY, "evidence" to listOf(mapOf("target" to mapOf("label" to HOME))))),
        )
        val ports = AgentPorts(
            FakeScreen(ScreenBuilder.screen(LAUNCHER) { text(HOME, top = 100) }),
            FakeEyes(),
            FakeData(),
            start.owner,
            Waiting(finishing, seconds * 1_000L),
            FakeApps(),
            FakeMemory(),
            FakeLog(),
            FakeClock(),
            start.goal,
            start.history,
        )
        return RoundSetup.Ready(ports, AgentSettings(), "task-${start.command}-standin", emptySet(), emptyFlow(), offersRoutines = false) {}
    }

    // A finished round, checked or not, answers with the reply.
    override fun sentence(report: TaskReport): String = when (report.end) {
        EndReason.FINISHED, EndReason.UNVERIFIED -> reply
        else -> AgentTexts.sentence(report, ScriptedModel.INFO.providerName)
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
        const val HOME = "Home"
        const val SUMMARY = "I read the reply."
    }
}
