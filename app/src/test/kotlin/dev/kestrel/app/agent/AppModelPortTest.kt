package dev.kestrel.app.agent

import dev.kestrel.providers.catalog.AuthMethod
import dev.kestrel.providers.catalog.LocalServerKind
import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.chat.CacheFields
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.ChatResponse
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.chat.StopReason
import dev.kestrel.providers.chat.Usage
import dev.kestrel.providers.client.PricedResponse
import dev.kestrel.providers.cost.Cost
import dev.kestrel.providers.discovery.DiscoveredModel
import dev.kestrel.providers.profiles.ModelRole
import dev.kestrel.providers.profiles.Profile
import dev.kestrel.providers.profiles.ProfileModel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppModelPortTest {
    private fun profile(providerId: String, lightweight: String, versatile: String = lightweight, region: String? = null, local: LocalServerKind? = null) =
        Profile("p1", "Mine", providerId, "key", AuthMethod.API_KEY, null, region = region, localServer = local,
            lightweight = ProfileModel(lightweight), versatile = ProfileModel(versatile))

    @Test
    fun theProfileChecksRefuseTheModelsThatCannotWork() {
        val vertex = profile("google-vertex", "claude-opus-5@20261001", region = "europe-west4")

        assertEquals(ProfileChecks.VERTEX_LOCATION, ProfileChecks.unusable(vertex, RequestStyle.VERTEX, ModelEntry("claude-opus-5@20261001", "Claude Opus 5")))
        assertNull(ProfileChecks.unusable(vertex.copy(region = "global"), RequestStyle.VERTEX, ModelEntry("claude-opus-5", "Claude Opus 5")))
        assertNull(ProfileChecks.unusable(vertex, RequestStyle.VERTEX, ModelEntry("claude-opus-5-5", "Claude Opus 5.5")))
        val arn = "arn:aws:bedrock:eu-west-1:123456789012:application-inference-profile/abc"
        assertEquals(ProfileChecks.BEDROCK_PROFILE, ProfileChecks.unusable(profile("amazon-bedrock", arn), RequestStyle.BEDROCK, ModelEntry(arn, arn)))
        val ollama = profile("ollama", "qwen3:4b", local = LocalServerKind.OLLAMA)
        assertEquals(ProfileChecks.OLLAMA_WINDOW, ProfileChecks.unusable(ollama, null, ModelEntry("qwen3:4b", "qwen3:4b", contextWindow = 8_192)))
        assertNull(ProfileChecks.unusable(ollama, null, ModelEntry("qwen3:4b", "qwen3:4b", contextWindow = 32_768)))
    }

    @Test
    fun theModelInfoTakesTheTasksLightweightModelAndTheSignedThinking() {
        val anthropic = profile("anthropic", "claude-haiku-4-5", versatile = "claude-sonnet-5-5")

        val info = ModelInfos.of(anthropic, null, emptyList(), lightweight = "claude-haiku-5-5")

        assertEquals("claude-haiku-5-5", info.lightweight.id)
        assertTrue(info.lightweight.signedThinking)
        assertTrue(info.versatile.signedThinking)
        assertFalse(ModelInfos.of(profile("github-copilot", "claude-sonnet-5-5"), null, emptyList()).versatile.signedThinking)
        assertFalse(ModelInfos.of(anthropic, null, emptyList()).lightweight.signedThinking)
        val local = ModelInfos.of(profile("ollama", "qwen3:4b", local = LocalServerKind.OLLAMA), null, listOf(DiscoveredModel("qwen3:4b", "qwen3:4b", contextWindow = 8_192)))
        assertTrue(local.localOllama)
        assertEquals(ProfileChecks.OLLAMA_WINDOW, local.lightweight.unusable)
    }

    @Test
    fun aModelWhoseExplicitCacheReadsNothingFromStepThreeLosesTheExplicitMode() = runTest {
        val sent = mutableListOf<CacheFields>()
        var usage = Usage(input = 500, output = 10, cacheRead = 0)
        val info = ModelInfos.of(profile("github-copilot", "gpt-6-luna", versatile = "gpt-6.1-terra"), null, emptyList())
        val port = AppModelPort(info, CacheState { CacheFields.ALL }) { _, request ->
            sent += request.cacheFields
            PricedResponse(ChatResponse(ChatMessage.Assistant(emptyList()), StopReason.END, usage, "gpt-6-luna"), Cost(0.0, 0.0, estimated = false, priceKnown = true, planUsage = false), RequestStyle.RESPONSES)
        }
        val request = ChatRequest("system", listOf(ChatMessage.User(listOf(Part.Text("hi")))), maxOutputTokens = 100)

        port.call(ModelRole.LIGHTWEIGHT, request, step = 3)
        usage = Usage(input = 2_000, output = 10, cacheRead = 0)
        port.call(ModelRole.LIGHTWEIGHT, request, step = 2)
        port.call(ModelRole.LIGHTWEIGHT, request, step = 3)
        port.call(ModelRole.LIGHTWEIGHT, request, step = 4)
        port.call(ModelRole.VERSATILE, request, step = 4)

        // The first request had too few tokens and the second came before step 3; the versatile model keeps the mode.
        assertEquals(listOf(true, true, true, false, true), sent.map { it.responsesExplicit })
    }
}
