package dev.kestrel.agent.testing

import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.profiles.ModelRole
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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

    // Task 5's review M1: a reply's requires or refuses that is not a list of texts, or a key that the model does not read,
    // would let a dry run's check pass without checking, so the script is refused when it is read, with an error that
    // names the reply and the key and never a text.
    @Test
    fun aScriptWithAMalformedRequiresOrRefusesOrAnUnknownKeyIsRefusedWhenItIsRead() {
        fun refused(reply: String): InvalidScript =
            assertThrows(InvalidScript::class.java) { ScriptedModel.fromJson("""{"replies": [{"tool": "wait"}, $reply]}""") }
        fun refusal(reply: String): String = refused(reply).message!!

        assertEquals(
            "Reply 2 of the script has a \"requires\" that is not a list of texts.",
            refusal("""{"tool": "wait", "requires": "This conversation so far:"}"""),
        )
        assertEquals("Reply 2 of the script has a \"refuses\" that is not a list of texts.", refusal("""{"tool": "wait", "refuses": [{"secret": 1}]}"""))
        assertEquals("Reply 2 of the script has a \"requires\" that is not a list of texts.", refusal("""{"tool": "wait", "requires": ["secret", 2]}"""))
        assertEquals(
            "Reply 2 of the script has keys that the scripted model does not know: require.",
            refusal("""{"tool": "wait", "require": ["This conversation so far:"]}"""),
        )
        // The reply's number and the keys, for a hook that names them (plan 7G's check of a series' scripts).
        val unknown = refused("""{"tool": "wait", "require": ["secret"], "credit": 1}""")
        assertEquals(2, unknown.reply)
        assertEquals(listOf("credit", "require"), unknown.keys)
    }
}
