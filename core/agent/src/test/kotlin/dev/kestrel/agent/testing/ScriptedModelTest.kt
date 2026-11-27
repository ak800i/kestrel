package dev.kestrel.agent.testing

import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.profiles.ModelRole
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** A scripted reply's texts that its request must hold or must not hold (plan 7G; design 15.3 of sub-project 7). */
class ScriptedModelTest {
    private fun request(context: String?, vararg messages: ChatMessage) =
        ChatRequest(system = "Kestrel", messages = messages.toList(), maxOutputTokens = 100, context = context)

    private suspend fun call(model: ScriptedModel, request: ChatRequest): AssistantPart.ToolCall =
        model.call(ModelRole.VERSATILE, request, 1).response.message.toolCalls.single()

    @Test
    fun aReplyIsGivenWhenItsRequestHoldsEveryTextThatItRequiresAndNoneThatItRefuses() = runTest {
        val script = """{"replies": [{"tool": "wait", "arguments": {"seconds": 1}, "requires": ["This conversation so far:", "gave 1 item"], "refuses": ["continued on"]}]}"""
        val matching = request(
            "This conversation so far:\nCommand 1, at 09:00 (spoken):",
            ChatMessage.User(listOf(Part.Text("read my latest SMS"))),
            ChatMessage.ToolResult("call-1", "list_sms", "list_sms gave 1 item"),
        )

        val call = call(ScriptedModel.fromJson(script), matching)

        assertEquals("wait", call.name)
        assertEquals("1", call.arguments["seconds"]!!.jsonPrimitive.content)
    }

    @Test
    fun aReplyWhoseRequestLacksATextOrHoldsARefusedOneFailsTheTaskWithAReasonThatNamesNoText() = runTest {
        val script = """{"replies": [{"tool": "wait", "arguments": {"seconds": 1}, "requires": ["This conversation so far:"], "refuses": ["continued on"]}]}"""
        fun reason(call: AssistantPart.ToolCall) = call.arguments["reason"]!!.jsonPrimitive.content

        val lacking = call(ScriptedModel.fromJson(script), request(null, ChatMessage.User(listOf(Part.Text("read my latest SMS")))))
        val refused = call(ScriptedModel.fromJson(script), request("This conversation so far:\nThis conversation was continued on Friday."))
        val usedUp = call(ScriptedModel.fromJson("""{"replies": []}"""), request(null))

        assertEquals("fail", lacking.name)
        assertEquals(ScriptedModel.MISMATCH, reason(lacking))
        assertEquals("fail", refused.name)
        assertEquals(ScriptedModel.MISMATCH, reason(refused))
        assertEquals(ScriptedModel.USED_UP, reason(usedUp))
    }
}
