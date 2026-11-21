package dev.kestrel.agent.loop

import dev.kestrel.agent.EndReason
import dev.kestrel.agent.Harness
import dev.kestrel.agent.Screens
import dev.kestrel.agent.testing.ScriptedModel
import dev.kestrel.agent.testing.ScriptedModel.Companion.call
import dev.kestrel.providers.chat.ToolChoice
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.profiles.ModelRole
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderErrorsTest {
    private fun error(name: String, toolChoice: Boolean = false): JsonObject = buildJsonObject {
        put("error", name)
        if (toolChoice) put("toolChoice", true)
    }

    private val stop = call("fail", null, null, null, "reason" to "Stop.")

    @Test
    fun aTimeoutOnTheLightweightModelRepeatsTheStepOnTheVersatileModel() = runTest {
        val harness = Harness(Screens.chats(), listOf(error("Timeout"), stop))

        val report = harness.run("Open Ana's chat")

        assertEquals(EndReason.FAILED_BY_MODEL, report.end)
        assertEquals(listOf(ModelRole.LIGHTWEIGHT, ModelRole.VERSATILE), harness.model.roles)
        assertEquals(harness.model.requests[0].messages, harness.model.requests[1].messages)
    }

    @Test
    fun aServerErrorOnTheVersatileModelEndsTheTaskWithIt() = runTest {
        val harness = Harness(Screens.chats(), listOf(error("RateLimited"), error("ServerError")))

        val report = harness.run("Open Ana's chat")

        assertEquals(EndReason.PROVIDER, report.end)
        assertEquals(ProviderError.ServerError(500), report.error)
    }

    @Test
    fun anUnreachableProviderAndAQuotaEndTheTaskAtOnce() = runTest {
        val unreachable = Harness(Screens.chats(), listOf(error("Unreachable")))
        assertEquals(ProviderError.Unreachable, unreachable.run("Open Ana's chat").error)
        assertEquals(1, unreachable.model.requests.size)

        val quota = Harness(Screens.chats(), listOf(error("QuotaOrPolicy")))
        assertEquals(EndReason.PROVIDER, quota.run("Open Ana's chat").end)
        assertEquals(listOf(ModelRole.LIGHTWEIGHT), quota.model.roles)
    }

    @Test
    fun aBadRequestAboutTheToolChoiceIsSentOnceMoreWithAutoAndAutoStays() = runTest {
        val harness = Harness(Screens.chats(), listOf(
            error("BadRequest", toolChoice = true),
            call("tap", "Ana Petrović", null, null, "reason" to "open", "category" to "reading"),
            stop,
        ))
        harness.screen.onTap("Ana Petrović", Screens.chat("Ana Petrović", "Hi"))

        harness.run("Open Ana's chat")

        assertEquals(listOf(ToolChoice.REQUIRED, ToolChoice.AUTO, ToolChoice.AUTO), harness.model.requests.map { it.toolChoice })
    }

    @Test
    fun anotherBadRequestEndsTheTaskUnlessTheModelKeepsSignedThinking() = runTest {
        val plain = Harness(Screens.chats(), listOf(error("BadRequest")))
        assertEquals(EndReason.PROVIDER, plain.run("Open Ana's chat").end)
        assertEquals(1, plain.model.requests.size)

        val claude = ScriptedModel.INFO.copy(lightweight = ScriptedModel.INFO.lightweight.copy(signedThinking = true))
        val thinking = Harness(Screens.chats(), listOf(error("BadRequest"), stop), model = claude)
        assertEquals(EndReason.FAILED_BY_MODEL, thinking.run("Open Ana's chat").end)
        assertEquals(2, thinking.model.requests.size)
    }

    @Test
    fun aMalformedReplyCountsAsAnInvalidCall() = runTest {
        val harness = Harness(Screens.chats(), listOf(error("Malformed"), error("Malformed"), stop))

        harness.run("Open Ana's chat")

        assertEquals(listOf(ModelRole.LIGHTWEIGHT, ModelRole.LIGHTWEIGHT, ModelRole.VERSATILE), harness.model.roles)
    }

    @Test
    fun theProfileChecksRefuseAModelBeforeTheTaskOrInsteadOfEscalating() = runTest {
        val vertex = "Claude Opus 5 and Sonnet 5 work on Vertex only in the global, us and eu locations. Change the profile's location or model."
        val light = ScriptedModel.INFO.copy(lightweight = ScriptedModel.INFO.lightweight.copy(unusable = vertex))
        val refused = Harness(Screens.chats(), emptyList(), model = light)
        val report = refused.run("Open Ana's chat")
        assertEquals(EndReason.MODEL_UNUSABLE, report.end)
        assertEquals(vertex, report.detail)
        assertTrue(refused.model.requests.isEmpty())

        val bedrock = "Kestrel can't use Bedrock application inference profiles yet. Choose the model by its own ID."
        val versatile = ScriptedModel.INFO.copy(versatile = ScriptedModel.INFO.versatile.copy(unusable = bedrock))
        val text = buildJsonObject { put("text", "Hmm.") }
        val stuck = Harness(Screens.chats(), listOf(text, text), model = versatile)
        val end = stuck.run("Open Ana's chat")
        assertEquals(EndReason.STUCK, end.end)
        assertEquals(bedrock, end.detail)
    }
}
