package dev.kestrel.providers.styles

import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.ChatAdapter
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.ChatResponse
import dev.kestrel.providers.chat.InvalidKind
import dev.kestrel.providers.chat.ModelTarget
import dev.kestrel.providers.chat.ReasoningState
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.chat.StopReason
import dev.kestrel.providers.chat.ToolChoice
import dev.kestrel.providers.chat.Usage
import dev.kestrel.providers.styles.AdapterHarness.Companion.GET_NUMBER
import dev.kestrel.providers.styles.AdapterHarness.Companion.OPEN_APP
import dev.kestrel.providers.styles.AdapterHarness.Companion.obj
import dev.kestrel.providers.styles.AdapterHarness.Companion.request
import dev.kestrel.providers.styles.AdapterHarness.Companion.target
import dev.kestrel.providers.styles.AdapterHarness.Companion.user
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonActionsTest {
    /** An adapter that answers with [reply] and keeps the request it got. */
    private class Scripted(private val reply: String, private val extra: List<AssistantPart> = emptyList()) : ChatAdapter {
        var sent: ChatRequest? = null

        override suspend fun complete(request: ChatRequest, target: ModelTarget): ChatResponse {
            sent = request
            return ChatResponse(ChatMessage.Assistant(extra + AssistantPart.Text(reply)), StopReason.END, Usage(10, 5), "m")
        }
    }

    private val model = target("ollama", RequestStyle.OLLAMA, "http://127.0.0.1:11434", ModelEntry("m", "M"))

    private fun run(reply: String, vararg history: ChatMessage, extra: List<AssistantPart> = emptyList()): Pair<ChatResponse, ChatRequest> {
        val inner = Scripted(reply, extra)
        val messages = if (history.isEmpty()) arrayOf<ChatMessage>(user("Open the camera.")) else arrayOf(*history)
        val response = runBlocking { JsonActions(inner).complete(request(*messages, tools = listOf(OPEN_APP, GET_NUMBER)) { copy(toolChoice = ToolChoice.REQUIRED) }, model) }
        return response to inner.sent!!
    }

    @Test
    fun theInnerAdapterGetsNoToolsTheInstructionAndTheActionSchema() {
        val (_, sent) = run("""{"tool":"get_number","arguments":{}}""")

        assertTrue(sent.tools.isEmpty())
        assertEquals(ToolChoice.AUTO, sent.toolChoice)
        assertTrue(sent.system.startsWith("You are Kestrel.\n\nYou act by choosing one of these actions."))
        assertTrue(sent.system.contains("- open_app: Opens an app by its name. Parameters: ${OPEN_APP.parameters}"))
        assertEquals(
            obj("""{"type":"object","properties":{"tool":{"type":"string","enum":["open_app","get_number"]},"arguments":{"type":"object"}},"required":["tool","arguments"]}"""),
            sent.jsonSchema,
        )
    }

    @Test
    fun anActionInACodeFenceBecomesAToolCallAndKeepsTheReasoning() {
        val thinking = AssistantPart.Reasoning(ReasoningState(RequestStyle.ANTHROPIC, "ollama", "m", obj("""{"type":"thinking"}""")))

        val (response, _) = run("Sure.\n```json\n{\"tool\": \"open_app\", \"arguments\": {\"name\": \"Camera\"}}\n```", extra = listOf(thinking))

        assertEquals(listOf(thinking, AssistantPart.ToolCall("json-1", "open_app", obj("""{"name":"Camera"}"""))), response.message.parts)
        assertEquals(StopReason.TOOL_CALL, response.stop)
        assertNull(response.invalidToolCall)
    }

    @Test
    fun theIdCountsTheEarlierJsonActions() {
        val earlier = ChatMessage.Assistant(listOf(AssistantPart.ToolCall("json-1", "get_number", obj("{}"))))

        val (response, _) = run(
            """{"tool":"open_app","arguments":{"name":"Clock"}} and more text""",
            user("Open the clock."), earlier, ChatMessage.ToolResult("json-1", "get_number", "7"),
        )

        assertEquals(listOf("json-2"), response.message.toolCalls.map { it.id })
    }

    @Test
    fun aReplyWithoutAValidActionKeepsItsTextAndSaysWhy() {
        val cases = mapOf(
            "I opened it." to InvalidKind.NO_ACTION,
            """{"tool": "open_app", "arguments": {"name": "Camera"}""" to InvalidKind.BAD_JSON,
            """{"tool": "open_app", "arguments": "Camera"}""" to InvalidKind.BAD_JSON,
            """{"tool": "close_app", "arguments": {}}""" to InvalidKind.UNKNOWN_TOOL,
            """{"tool": "open_app", "arguments": {}}""" to InvalidKind.MISSING_ARGUMENT,
        )
        for ((reply, kind) in cases) {
            val (response, _) = run(reply)
            assertEquals(reply, kind, response.invalidToolCall?.kind)
            assertEquals(reply, response.invalidToolCall?.text)
            assertEquals(listOf(AssistantPart.Text(reply)), response.message.parts)
        }
    }

    @Test
    fun aBraceInsideAStringDoesNotEndTheAction() {
        val (response, _) = run("""{"tool":"open_app","arguments":{"name":"Notes {draft}"}}""")

        assertEquals(obj("""{"name":"Notes {draft}"}"""), response.message.toolCalls.single().arguments)
    }

    @Test
    fun theIdIsNumberedAfterTheHighestInATrimmedHistory() {
        val kept = ChatMessage.Assistant(listOf(AssistantPart.ToolCall("json-2", "get_number", obj("{}"))))

        val (response, _) = run("""{"tool":"get_number","arguments":{}}""", user("Get the number."), kept, ChatMessage.ToolResult("json-2", "get_number", "7"), user("Again."))

        assertEquals("json-3", response.message.toolCalls.single().id)
    }

    @Test
    fun aRequestWithoutToolsOrWithToolChoiceNoneGoesToTheInnerAdapterUnchanged() {
        val inner = Scripted("Hello.")
        val none = request(user("Hi"), tools = listOf(OPEN_APP)) { copy(toolChoice = ToolChoice.NONE) }
        val empty = request(user("Hi"), tools = emptyList())

        val answers = runBlocking { listOf(none, empty).map { JsonActions(inner).complete(it, model).also { _ -> assertEquals(it, inner.sent) } } }

        assertTrue(answers.all { it.invalidToolCall == null && it.message.parts == listOf(AssistantPart.Text("Hello.")) })
    }
}
