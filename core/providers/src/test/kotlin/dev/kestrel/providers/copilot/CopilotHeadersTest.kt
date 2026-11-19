package dev.kestrel.providers.copilot

import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.ImageType
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.http.RequestMeta
import dev.kestrel.providers.signin.SignInFixtures.catalog
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `x-initiator`, the vision header and the runtime headers of Copilot's requests (design D12; research r27 §6). */
class CopilotHeadersTest {
    private val runtime = catalog.overrides.copilot.runtimeHeaders
    private val ask = ChatMessage.User(listOf(Part.Text("Open the settings.")))

    private fun request(vararg messages: ChatMessage, agentStep: Boolean = false) =
        ChatRequest("You are a test.", messages.toList(), maxOutputTokens = 100, meta = RequestMeta(agentStep = agentStep))

    @Test
    fun aTasksFirstRequestIsTheUsersAndEveryLaterStepOrToolResultIsTheAgents() {
        val headers = CopilotHeaders.forRequests(runtime)
        val call = ChatMessage.Assistant(listOf(AssistantPart.ToolCall("call-1", "tap", JsonObject(emptyMap()))))
        val result = ChatMessage.ToolResult("call-1", "tap", "done")

        assertEquals("user", headers.headers(request(ask))["x-initiator"])
        assertEquals("agent", headers.headers(request(ask, agentStep = true))["x-initiator"])
        assertEquals("agent", headers.headers(request(ask, call, result))["x-initiator"])
    }

    @Test
    fun anImageAddsTheVisionHeaderAndTheRuntimeHeadersAreAlwaysSent() {
        val screenshot = ChatMessage.User(listOf(Part.Text("What is on the screen?"), Part.Image(ImageType.PNG, byteArrayOf(1, 2))))

        val sent = CopilotHeaders.forRequests(runtime, mapOf("X-Test" to "1")).headers(request(screenshot))

        assertEquals("true", sent["Copilot-Vision-Request"])
        assertEquals("GitHubCopilotChat/0.35.0", sent["User-Agent"])
        assertEquals("copilot-developer-cli", sent["Copilot-Integration-Id"])
        assertEquals("1", sent["X-Test"])
        assertNull(sent["Openai-Intent"])
        assertNull(CopilotHeaders.forRequests(runtime).headers(request(ask))["Copilot-Vision-Request"])
    }
}
