package dev.kestrel.conversation.testing

import dev.kestrel.agent.AgentPorts
import dev.kestrel.agent.AgentSettings
import dev.kestrel.agent.Ledger
import dev.kestrel.agent.TaskReport
import dev.kestrel.agent.port.ModelInfo
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
import dev.kestrel.conversation.ConversationTexts
import dev.kestrel.conversation.goal.GoalEnvironment
import dev.kestrel.conversation.goal.RoundSetup
import dev.kestrel.conversation.goal.RoundStart
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.client.PricedResponse
import dev.kestrel.providers.profiles.ModelRole
import dev.kestrel.safety.SafetySettings
import dev.kestrel.screen.model.ScreenSnapshot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.JsonObject

/**
 * One phone for every round of a conversation, as the tests script it (design D27 of sub-project 5): the agent's fakes,
 * and one scripted model whose replies serve the conversation's rounds in order. [afterCall] runs after each model reply
 * with the number of requests so far: a test may steer or touch there. [sentenceOf] is the sentence of every report.
 */
class FakeGoalEnvironment(
    replies: List<JsonObject>,
    first: ScreenSnapshot? = HOME,
    var settings: AgentSettings = AgentSettings(safety = SafetySettings(testMode = false)),
) : GoalEnvironment {
    val screen = FakeScreen(first).apply { installed = FakeApps.DEFAULT.mapTo(mutableSetOf()) { it.packageName } }
    val data = FakeData()
    val eyes = FakeEyes()
    val apps = FakeApps()
    val memory = FakeMemory()
    val log = FakeLog()
    val clock = FakeClock()
    val model = ScriptedModel(replies)
    val touches = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    val starts = mutableListOf<RoundStart>()
    var refusal: RoundSetup.Refused? = null
    var knownNames: Set<String> = emptySet()
    var offersRoutines = false
    var released = 0
    var afterCall: (Int) -> Unit = {}

    private val watched: ModelPort = Watched(model) { afterCall(it) }

    override suspend fun prepare(start: RoundStart): RoundSetup {
        starts += start
        refusal?.let { return it }
        val ports = AgentPorts(screen, eyes, data, start.owner, watched, apps, memory, log, clock, start.goal, start.history)
        return RoundSetup.Ready(ports, settings, "task-${starts.size}-0a1b2c3d", knownNames, touches, offersRoutines) { released++ }
    }

    override fun sentence(report: TaskReport): String = sentenceOf(report)

    override fun toString(): String = "FakeGoalEnvironment(starts=${starts.size})"

    private class Watched(private val scripted: ScriptedModel, private val after: (Int) -> Unit) : ModelPort {
        override val model: ModelInfo get() = scripted.model

        override suspend fun call(role: ModelRole, request: ChatRequest, step: Int): PricedResponse =
            scripted.call(role, request, step).also { after(scripted.requests.size) }
    }

    companion object {
        /** A fixture app's screen with one text, "Hello". */
        val HOME: ScreenSnapshot = ScreenBuilder.screen("dev.kestrel.testapp", "Kestrel fixtures") { text("Hello", 300) }

        fun sentenceOf(report: TaskReport): String =
            "Ended ${report.end.name.lowercase()}: ${Ledger.sentence(report.ledger) ?: ConversationTexts.NOTHING_CHANGED}"
    }
}
