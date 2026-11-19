package dev.kestrel.providers.styles

import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.chat.Usage
import dev.kestrel.providers.styles.AdapterHarness.Companion.request
import dev.kestrel.providers.styles.AdapterHarness.Companion.target
import dev.kestrel.providers.styles.AdapterHarness.Companion.user
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptersTest {
    private val bench = AdapterHarness()

    @After
    fun close() = bench.close()

    @Test
    fun eachStyleHasItsAdapter() {
        val expected = mapOf(
            RequestStyle.CHAT_COMPLETIONS to ChatCompletionsAdapter::class,
            RequestStyle.RESPONSES to ResponsesAdapter::class,
            RequestStyle.ANTHROPIC to AnthropicAdapter::class,
            RequestStyle.GEMINI to GeminiAdapter::class,
            RequestStyle.OLLAMA to OllamaAdapter::class,
            RequestStyle.BEDROCK to BedrockAdapter::class,
            RequestStyle.VERTEX to VertexAdapter::class,
            RequestStyle.AZURE to AzureAdapter::class,
        )
        for (style in RequestStyle.entries) assertEquals(expected[style], Adapters.forStyle(style, bench.http)::class)
    }

    /** Design D6: each style's own usage fields, normalised to uncached input, output, cache reads and writes, and reasoning. */
    @Test
    fun theUsageOfEveryStyleIsNormalisedAsDesignD6Says() = runBlocking {
        val cases = listOf(
            Triple(RequestStyle.CHAT_COMPLETIONS, "chat-completions/text.sse", Usage(input = 100, output = 40, cacheRead = 800, cacheWrite = 300, reasoning = 25)),
            Triple(RequestStyle.RESPONSES, "responses/text.sse", Usage(input = 300, output = 50, cacheRead = 1500, cacheWrite = 200, reasoning = 30)),
            Triple(RequestStyle.ANTHROPIC, "anthropic/text.sse", Usage(input = 50, output = 42, cacheRead = 2000, cacheWrite = 1000)),
            Triple(RequestStyle.GEMINI, "gemini/text.sse", Usage(input = 410, output = 50, cacheRead = 600, reasoning = 20)),
            Triple(RequestStyle.OLLAMA, "ollama/text.json", Usage(input = 20, output = 8, cacheRead = 100)),
            Triple(RequestStyle.BEDROCK, "bedrock/text.json", Usage(input = 100, output = 12, cacheRead = 900, cacheWrite = 50)),
            Triple(RequestStyle.VERTEX, "vertex/gemini-text.sse", Usage(input = 40, output = 8)),
            Triple(RequestStyle.AZURE, "azure/chat-text.sse", Usage(input = 20, output = 5)),
        )
        val bases = mapOf(
            RequestStyle.CHAT_COMPLETIONS to "https://api.openai.com/v1",
            RequestStyle.RESPONSES to "https://api.openai.com/v1",
            RequestStyle.ANTHROPIC to "https://api.anthropic.com",
            RequestStyle.GEMINI to "https://generativelanguage.googleapis.com/v1beta",
            RequestStyle.OLLAMA to "http://127.0.0.1:11434",
            RequestStyle.BEDROCK to "https://bedrock-runtime.us-east-1.amazonaws.com",
            RequestStyle.VERTEX to VertexUrls.base("global", "kestrel-test-project").toString(),
            RequestStyle.AZURE to "https://kestrel.openai.azure.com",
        )
        for ((style, fixture, usage) in cases) {
            bench.reply(fixture)
            val model = if (style == RequestStyle.AZURE) ModelEntry("Mistral-Large-3", "Mistral Large 3") else ModelEntry("m", "M")
            val response = Adapters.forStyle(style, bench.http).complete(request(user("Hi")), target("test-provider", style, bases.getValue(style), model))
            assertEquals(style.name, usage, response.usage)
        }
    }
}
