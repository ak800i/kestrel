package dev.kestrel.providers.chat

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ChatRequestsTest {
    private val call = AssistantPart.ToolCall("call-1", "tap", buildJsonObject { put("label", "Send") })
    private val other = AssistantPart.ToolCall("call-2", "scroll", buildJsonObject { put("direction", "down") })

    private fun request(vararg messages: ChatMessage) =
        ChatRequest(system = "You drive the phone.", messages = messages.toList(), maxOutputTokens = 100)

    @Test
    fun aToolCallWithItsResultPasses() {
        ChatRequests.check(
            request(
                ChatMessage.User(listOf(Part.Text("Open the mail."))),
                ChatMessage.Assistant(listOf(call, other)),
                ChatMessage.ToolResult("call-2", "scroll", "Scrolled."),
                ChatMessage.ToolResult("call-1", "tap", "Tapped."),
                ChatMessage.User(listOf(Part.Text("The screen now shows the inbox."))),
            ),
        )
    }

    @Test
    fun aToolCallWithoutItsResultBeforeTheNextMessageFails() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ChatRequests.check(
                request(
                    ChatMessage.Assistant(listOf(call, other)),
                    ChatMessage.ToolResult("call-1", "tap", "Tapped."),
                    ChatMessage.User(listOf(Part.Text("Next."))),
                    ChatMessage.ToolResult("call-2", "scroll", "Too late."),
                ),
            )
        }

        assertEquals("Message 0 has 1 tool calls without a result", error.message)
    }

    @Test
    fun aToolCallAtTheEndWithoutAResultFails() {
        assertThrows(IllegalArgumentException::class.java) { ChatRequests.check(request(ChatMessage.Assistant(listOf(call)))) }
    }

    @Test
    fun twoCallsWithOneIdAndANonPositiveLimitFail() {
        assertThrows(IllegalArgumentException::class.java) {
            ChatRequests.check(
                request(
                    ChatMessage.Assistant(listOf(call, call.copy(name = "scroll"))),
                    ChatMessage.ToolResult("call-1", "tap", "Tapped."),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChatRequests.check(request(ChatMessage.User(listOf(Part.Text("Hi.")))).copy(maxOutputTokens = 0))
        }
    }

    @Test
    fun aRequestPrintsOnlyCountsAndLengths() {
        val request = request(ChatMessage.User(listOf(Part.Text("Hi.")))).copy(
            system = "secret system prompt",
            cacheKey = "secret key",
            temperature = 0.2,
            reasoning = ReasoningLevel.LOW,
            context = "secret context",
            cacheAfter = 0,
        )

        val printed = request.toString()

        assertFalse(printed, printed.contains("secret"))
        assertEquals(
            "ChatRequest(systemLength=20, messages=1, tools=0, toolChoice=AUTO, reasoning=LOW, maxOutputTokens=100, " +
                "temperature=set, cacheKey=set, jsonSchema=none, context=14 chars, cacheAfter=0, cacheFields=$CACHE_ALL)",
            printed,
        )
    }

    @Test
    fun aToolResultWithoutAnEarlierCallFails() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ChatRequests.check(
                request(
                    ChatMessage.User(listOf(Part.Text("Open the mail."))),
                    ChatMessage.Assistant(listOf(call)),
                    ChatMessage.ToolResult("call-1", "tap", "Tapped."),
                    ChatMessage.ToolResult("call-9", "tap", "A result whose call was removed."),
                ),
            )
        }

        assertEquals("Message 3 is a tool result without its call", error.message)
    }

    @Test
    fun cacheAfterMustNameAMessage() {
        val one = request(ChatMessage.User(listOf(Part.Text("Hi."))))

        ChatRequests.check(one.copy(cacheAfter = 0))
        assertThrows(IllegalArgumentException::class.java) { ChatRequests.check(one.copy(cacheAfter = 1)) }
        assertThrows(IllegalArgumentException::class.java) { ChatRequests.check(one.copy(cacheAfter = -1)) }
    }

    private companion object {
        const val CACHE_ALL = "CacheFields(anthropicMarks=true, responsesExplicit=true, chatCopilotMarks=true)"
    }
}
