package dev.kestrel.agent

import dev.kestrel.agent.port.LauncherApp
import dev.kestrel.agent.port.ModelInfo
import dev.kestrel.agent.testing.FakeApps
import dev.kestrel.agent.testing.FakeClock
import dev.kestrel.agent.testing.FakeData
import dev.kestrel.agent.testing.FakeEyes
import dev.kestrel.agent.testing.FakeLog
import dev.kestrel.agent.testing.FakeMemory
import dev.kestrel.agent.testing.FakeOwner
import dev.kestrel.agent.testing.FakeScreen
import dev.kestrel.agent.testing.ScriptedModel
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.Part
import dev.kestrel.safety.SafetySettings
import dev.kestrel.screen.model.ScreenSnapshot
import kotlinx.serialization.json.JsonObject

/**
 * One agent with fakes for every port, for the JVM tests (design D27). Test mode is off unless a test turns it on, so that
 * the tests judge Standard mode's decisions; the tests of test mode set it.
 */
class Harness(
    first: ScreenSnapshot?,
    replies: List<JsonObject>,
    var settings: AgentSettings = AgentSettings(safety = SafetySettings(testMode = false)),
    model: ModelInfo = ScriptedModel.INFO,
    launcher: List<LauncherApp> = FakeApps.DEFAULT,
) {
    val screen = FakeScreen(first).apply { installed = launcher.mapTo(mutableSetOf()) { it.packageName } }
    val owner = FakeOwner()
    val data = FakeData()
    val apps = FakeApps(launcher)
    val memory = FakeMemory()
    val log = FakeLog()
    val clock = FakeClock()
    val eyes = FakeEyes()
    val model = ScriptedModel(replies, model)
    val reports = mutableListOf<TaskReport>()

    val agent: Agent get() = Agent(AgentPorts(screen, eyes, data, owner, model, apps, memory, log, clock), settings)

    /** Runs [text] as a task to its end and returns its one report. */
    suspend fun run(text: String, knownNames: Set<String> = emptySet()): TaskReport {
        agent.run(TaskRequest("task-1-0a1b2c3d", text, knownNames)) { reports += it }
        return reports.single()
    }

    /** Every text that went to the model in the newest request: the context and the messages' texts. */
    fun lastRequestText(): String = requestText(model.requests.lastIndex)

    /** Every text that went to the model in request number [index] (0 for the first). */
    fun requestText(index: Int): String = model.requests[index].let { request ->
        (listOf(request.context.orEmpty()) + request.messages.mapNotNull { message ->
            when (message) {
                is ChatMessage.ToolResult -> message.text
                is ChatMessage.User -> message.parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text }
                is ChatMessage.Assistant -> null
            }
        }).joinToString("\n")
    }

    override fun toString(): String = "Harness"
}
