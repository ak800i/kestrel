package dev.kestrel.agent.loop

import dev.kestrel.agent.observe.ObservationTable
import dev.kestrel.agent.observe.TableEntry
import dev.kestrel.agent.tools.AgentCall
import dev.kestrel.agent.tools.ParsedCall
import dev.kestrel.agent.tools.ToolArguments
import dev.kestrel.agent.tools.Tools
import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatResponse
import dev.kestrel.providers.chat.InvalidKind
import dev.kestrel.providers.chat.InvalidToolCall
import dev.kestrel.providers.chat.ReasoningLevel
import dev.kestrel.providers.chat.StopReason
import dev.kestrel.providers.chat.ToolChoice
import dev.kestrel.providers.chat.Usage
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.safety.Category
import dev.kestrel.safety.Vault
import dev.kestrel.screen.model.IntRect
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallsTest {
    private val vault = Vault()
    private val table = ObservationTable(listOf(TableEntry(
        number = 1, key = "k1", label = "Send", shownLabel = "\"Send\"", className = "android.widget.Button",
        packageName = "com.viber.voip", windowId = 1, bounds = IntRect(900, 2100, 1040, 2220), editable = false,
        password = false, checkable = false, checked = false, scrollable = false, scrollAxis = null, ocr = false,
    )))

    private fun tap(target: Int) = buildJsonObject {
        put("target", target)
        put("reason", "send the message")
        put("category", "sending")
    }

    private fun response(vararg parts: AssistantPart, stop: StopReason = StopReason.TOOL_CALL, invalid: InvalidToolCall? = null) =
        ChatResponse(ChatMessage.Assistant(parts.toList()), stop, Usage(10, 5), "model", invalid)

    private fun call(id: String, name: String, arguments: JsonObject) = AssistantPart.ToolCall(id, name, arguments)

    @Test
    fun theFirstCallRunsAndEveryOtherCallIsAnsweredWithoutCountingAsInvalid() {
        val reply = ToolCalls.read(response(call("a", Tools.TAP, tap(1)), call("b", Tools.BACK, buildJsonObject { put("reason", "x") })), table, vault)

        reply as Reply.Act
        assertEquals("a", reply.callId)
        assertEquals(AgentCall.Tap(1, "send the message", Category.SENDING), reply.call)
        assertEquals(listOf(ResultText("b", Tools.BACK, Redacted.kestrel("Not run: Kestrel runs one action per step."), failed = true)), reply.notRun)
    }

    @Test
    fun aReplyWithTextAndNoCallGetsOneReprompt() {
        val reply = ToolCalls.read(response(AssistantPart.Text("Shall I send it?"), stop = StopReason.END), table, vault)

        assertEquals(Reply.Invalid(emptyList(), Redacted.kestrel("Reply with exactly one tool call.")), reply)
    }

    @Test
    fun aCallThatTheAdapterCouldNotUseGetsKestrelsSentenceAndNeverTheModelsText() {
        val reply = ToolCalls.read(response(stop = StopReason.END, invalid = InvalidToolCall(InvalidKind.UNKNOWN_TOOL, "delete_everything(now)")), table, vault)

        reply as Reply.Invalid
        assertEquals("That tool does not exist. Reply with exactly one tool call.", reply.reprompt!!.text)
        assertFalse(reply.reprompt!!.text.contains("delete_everything"))
        InvalidKind.entries.forEach { assertFalse(ToolCalls.sentence(it).isBlank()) }
    }

    @Test
    fun aCallWhoseArgumentsAreWrongGetsAFailedResultWithWhatWasWrong() {
        val expected = ToolArguments.parse(Tools.TAP, tap(9), table, vault) as ParsedCall.Invalid

        val reply = ToolCalls.read(response(call("a", Tools.TAP, tap(9)), call("b", Tools.TAP, tap(1))), table, vault)

        reply as Reply.Invalid
        assertEquals(listOf("a", "b"), reply.results.map { it.callId })
        assertEquals(expected.reason, reply.results[0].text.text)
        assertEquals(ToolCalls.NOT_RUN, reply.results[1].text.text)
        assertTrue(reply.results.all { it.failed })
        assertNull(reply.reprompt)
    }

    @Test
    fun aCutOrFilteredReplyIsInvalidEvenWithACall() {
        val cut = ToolCalls.read(response(call("a", Tools.TAP, tap(1)), stop = StopReason.MAX_TOKENS), table, vault) as Reply.Invalid
        val filtered = ToolCalls.read(response(stop = StopReason.FILTERED), table, vault) as Reply.Invalid

        assertEquals(listOf(ResultText("a", Tools.TAP, Redacted.kestrel(ToolCalls.CUT), failed = true)), cut.results)
        assertEquals("The provider's filter stopped the reply. Reply with exactly one tool call.", filtered.reprompt!!.text)
    }

    @Test
    fun theToolChoiceIsRequiredUntilAReasoningLevelOrA400AboutItSaysOtherwise() {
        val choices = ToolChoices()

        assertEquals(ToolChoice.REQUIRED, choices.choice("gpt-6-luna", null))
        assertEquals(ToolChoice.AUTO, choices.choice("gpt-6.1-terra", ReasoningLevel.LOW))
        assertFalse(choices.fallBack("gpt-6-luna", ToolChoice.REQUIRED, ProviderError.BadRequest(400, null)))
        assertFalse(choices.fallBack("gpt-6-luna", ToolChoice.REQUIRED, ProviderError.Timeout))
        assertEquals(ToolChoice.REQUIRED, choices.choice("gpt-6-luna", null))

        assertTrue(choices.fallBack("gpt-6-luna", ToolChoice.REQUIRED, ProviderError.BadRequest(400, null, toolChoice = true)))
        assertEquals(ToolChoice.AUTO, choices.choice("gpt-6-luna", null))
        assertFalse(choices.fallBack("gpt-6-luna", ToolChoice.AUTO, ProviderError.BadRequest(400, null, toolChoice = true)))
        assertEquals(ToolChoice.REQUIRED, choices.choice("claude-haiku-5-5", null))
    }
}
