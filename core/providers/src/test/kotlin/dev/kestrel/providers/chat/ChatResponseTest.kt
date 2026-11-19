package dev.kestrel.providers.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ChatResponseTest {
    @Test
    fun aResponsePrintsNoReplyNoModelIdAndNoInvalidText() {
        val response = ChatResponse(
            message = ChatMessage.Assistant(listOf(AssistantPart.Text("secret reply"))),
            stop = StopReason.END,
            usage = Usage(input = 10, output = 2),
            modelId = "arn:aws:bedrock:eu-west-1:123456789012:inference-profile/x",
            invalidToolCall = InvalidToolCall(InvalidKind.BAD_JSON, "secret reply"),
        )

        val printed = response.toString()

        assertFalse(printed, printed.contains("secret") || printed.contains("123456789012"))
        assertEquals(
            "ChatResponse(stop=END, message=Assistant(parts=[Text(length=12)]), " +
                "usage=Usage(input=10, output=2, cacheRead=0, cacheWrite=0, reasoning=0, estimated=false), " +
                "modelIdLength=58, invalidToolCall=InvalidToolCall(kind=BAD_JSON, textLength=12))",
            printed,
        )
    }

    @Test
    fun theWholeInputCountsTheCacheToo() {
        assertEquals(12, Usage(input = 5, output = 1, cacheRead = 4, cacheWrite = 3).totalInput)
    }
}
