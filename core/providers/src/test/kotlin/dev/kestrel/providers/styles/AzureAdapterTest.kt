package dev.kestrel.providers.styles

import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.ReasoningLevel
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.chat.StyleOptions
import dev.kestrel.providers.http.ApiKeyAuth
import dev.kestrel.providers.http.KeyHeader
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.styles.AdapterHarness.Companion.errorOf
import dev.kestrel.providers.styles.AdapterHarness.Companion.json
import dev.kestrel.providers.styles.AdapterHarness.Companion.request
import dev.kestrel.providers.styles.AdapterHarness.Companion.target
import dev.kestrel.providers.styles.AdapterHarness.Companion.user
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AzureAdapterTest {
    private val bench = AdapterHarness()
    private val adapter = AzureAdapter(bench.http)

    @After
    fun close() = bench.close()

    private fun deployment(name: String, endpoint: String, header: KeyHeader, options: StyleOptions = StyleOptions()) =
        target("microsoft-foundry", RequestStyle.AZURE, endpoint, ModelEntry(name, name), ApiKeyAuth("test-key", header), options)

    @Test
    fun aGptDeploymentUsesResponsesAtTheV1BaseWithTheApiKeyHeader() = runBlocking {
        bench.reply("azure/responses-text.sse")

        val response = adapter.complete(request(user("Hi")), deployment("gpt-6-luna", "https://kestrel.openai.azure.com/", KeyHeader.API_KEY))

        assertEquals(listOf(AssistantPart.Text("Hello from Azure.")), response.message.parts)
        assertEquals(listOf("https://kestrel.openai.azure.com/openai/v1/responses".toHttpUrl()), bench.calls.requests)
        val recorded = bench.take()
        assertEquals("test-key", recorded.headers["api-key"])
        assertNull(recorded.headers["Authorization"])
        assertEquals(json(""""gpt-6-luna""""), bench.body(recorded)["model"])
    }

    @Test
    fun anotherDeploymentUsesChatCompletionsWithoutAnApiVersion() = runBlocking {
        bench.reply("azure/chat-text.sse")

        val response = adapter.complete(request(user("Hi")), deployment("Mistral-Large-3", "https://kestrel.services.ai.azure.com/openai/v1/", KeyHeader.API_KEY))

        assertEquals(listOf(AssistantPart.Text("Hello from Foundry.")), response.message.parts)
        assertEquals(listOf("https://kestrel.services.ai.azure.com/openai/v1/chat/completions".toHttpUrl()), bench.calls.requests)
        val body = bench.body(bench.take())
        assertEquals(json(""""Mistral-Large-3""""), body["model"])
        // OpenClaw's requiresFoundryMaxCompletionTokens: only gpt-5, o1, o3 and o4 deployments get max_completion_tokens.
        assertEquals(json("256"), body["max_tokens"])
        assertNull(body["max_completion_tokens"])
    }

    @Test
    fun aClaudeDeploymentUsesFoundrysAnthropicRouteWithXApiKey() = runBlocking {
        bench.reply("azure/anthropic-text.sse")

        val response = adapter.complete(request(user("Hi")), deployment("claude-sonnet-5", "https://kestrel.services.ai.azure.com", KeyHeader.X_API_KEY))

        assertEquals(listOf(AssistantPart.Text("Hello from Claude on Foundry.")), response.message.parts)
        assertEquals(listOf("https://kestrel.services.ai.azure.com/anthropic/v1/messages".toHttpUrl()), bench.calls.requests)
        val recorded = bench.take()
        assertEquals("test-key", recorded.headers["x-api-key"])
        assertEquals("2023-06-01", recorded.headers["anthropic-version"])
    }

    @Test
    fun theOwnersChoiceOfApiWinsOverTheName() = runBlocking {
        bench.reply("azure/chat-text.sse")

        adapter.complete(request(user("Hi")), deployment("gpt-6-luna", "https://kestrel.openai.azure.com", KeyHeader.API_KEY, StyleOptions(wire = RequestStyle.CHAT_COMPLETIONS)))

        assertEquals(listOf("https://kestrel.openai.azure.com/openai/v1/chat/completions".toHttpUrl()), bench.calls.requests)
        assertEquals(json("256"), bench.body(bench.take())["max_completion_tokens"])
    }

    @Test
    fun aGpt6DeploymentOnResponsesGetsTheGenericEffortsAsOpenClawGivesAzure() = runBlocking {
        bench.reply("azure/responses-text.sse")
        val model = ModelEntry("gpt-6-luna", "gpt-6-luna", reasoning = true, thinkingLevelMap = mapOf("minimal" to "minimal"))
        val luna = target("microsoft-foundry", RequestStyle.AZURE, "https://kestrel.openai.azure.com", model, ApiKeyAuth("test-key", KeyHeader.API_KEY))

        adapter.complete(request(user("Hi")) { copy(reasoning = ReasoningLevel.MINIMAL) }, luna)

        // OpenClaw's resolveOpenAIModelReasoningEfforts gives GPT-6 on Azure's Responses API no GPT-6 list (which lacks
        // `minimal`), so the generic efforts and the ones that the model's own map names apply.
        assertEquals(json("""{"effort":"minimal"}"""), bench.body(bench.take())["reasoning"])
    }

    @Test
    fun aGpt6DeploymentOnFoundryKeepsTheGpt6EffortsAsOpenClawsFoundryPluginDoes() = runBlocking {
        bench.reply("azure/responses-text.sse")
        val model = ModelEntry("gpt-6-luna", "gpt-6-luna", reasoning = true, thinkingLevelMap = mapOf("minimal" to "minimal"))
        val luna = target("microsoft-foundry", RequestStyle.AZURE, "https://kestrel.services.ai.azure.com", model, ApiKeyAuth("test-key", KeyHeader.API_KEY))

        adapter.complete(request(user("Hi")) { copy(reasoning = ReasoningLevel.MINIMAL) }, luna)

        // OpenClaw's Foundry plugin sends openai-responses, whose GPT-6 list has no `minimal`, so the nearest effort is `low`.
        assertEquals(json("""{"effort":"low"}"""), bench.body(bench.take())["reasoning"])
    }

    @Test
    fun aGpt35DeploymentOnChatCompletionsKeepsMaxTokens() = runBlocking {
        bench.reply("azure/chat-text.sse")

        adapter.complete(request(user("Hi")), deployment("gpt-35-turbo", "https://kestrel.openai.azure.com", KeyHeader.API_KEY, StyleOptions(wire = RequestStyle.CHAT_COMPLETIONS)))

        val body = bench.body(bench.take())
        assertEquals(json("256"), body["max_tokens"])
        assertNull(body["max_completion_tokens"])
    }

    @Test
    fun anEndpointOrAnApiThatAzureDoesNotServeFailsWithoutARequest() {
        val cases = listOf(
            deployment("gpt-6-luna", "https://example.com/openai/v1?api-version=1", KeyHeader.API_KEY),
            deployment("gpt-6-luna", "https://kestrel.openai.azure.com:8443/openai/v1", KeyHeader.API_KEY),
            deployment("gemini-3.8-flash", "https://kestrel.services.ai.azure.com", KeyHeader.API_KEY, StyleOptions(wire = RequestStyle.GEMINI)),
        )
        for (case in cases) assertEquals(ProviderError.NotAllowed, errorOf(adapter, request(user("Hi")), case))
        assertEquals(0, bench.server.requestCount)
    }
}
