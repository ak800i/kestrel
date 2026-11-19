package dev.kestrel.providers.chat

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ChatMessageTest {
    @Test
    fun messagesPrintNoTextNoArgumentsAndNoReasoning() {
        val secret = "the owner's PIN is 4711"
        val messages = listOf(
            ChatMessage.User(listOf(Part.Text(secret), Part.Image(ImageType.PNG, byteArrayOf(1, 2, 3)))),
            ChatMessage.Assistant(
                listOf(
                    AssistantPart.Text(secret),
                    AssistantPart.ToolCall("id-$secret", "type_text", buildJsonObject { put("text", secret) }),
                    AssistantPart.Reasoning(ReasoningState(RequestStyle.ANTHROPIC, "anthropic", "claude-$secret", JsonPrimitive(secret))),
                ),
            ),
            ChatMessage.ToolResult("id-$secret", "type_text", secret),
        )

        val printed = messages.toString()

        assertFalse(printed, printed.contains("4711"))
        assertEquals(
            "[User(parts=[Text(length=23), Image(type=PNG, bytes=3)]), " +
                "Assistant(parts=[Text(length=23), ToolCall(nameLength=9, idLength=26, arguments=1), " +
                "Reasoning(ReasoningState(style=ANTHROPIC, provider=anthropic, modelIdLength=30, payloadLength=25))]), " +
                "ToolResult(nameLength=9, callIdLength=26, textLength=23, failed=false)]",
            printed,
        )
    }

    @Test
    fun imagesCompareByTheirBytes() {
        assertEquals(Part.Image(ImageType.JPEG, byteArrayOf(9, 8)), Part.Image(ImageType.JPEG, byteArrayOf(9, 8)))
        assertNotEquals(Part.Image(ImageType.JPEG, byteArrayOf(9, 8)), Part.Image(ImageType.PNG, byteArrayOf(9, 8)))
        assertEquals("image/jpeg", ImageType.JPEG.mime)
    }

    @Test
    fun anAssistantMessageListsItsToolCallsInOrder() {
        val first = AssistantPart.ToolCall("a", "tap", buildJsonObject { })
        val second = AssistantPart.ToolCall("b", "scroll", buildJsonObject { })

        assertEquals(listOf(first, second), ChatMessage.Assistant(listOf(AssistantPart.Text("x"), first, second)).toolCalls)
    }
}
