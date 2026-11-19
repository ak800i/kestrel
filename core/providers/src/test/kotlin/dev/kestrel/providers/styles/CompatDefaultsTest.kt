package dev.kestrel.providers.styles

import dev.kestrel.providers.catalog.CompatFlags
import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.chat.StyleOptions
import dev.kestrel.providers.styles.AdapterHarness.Companion.target
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The derived compat flags of OpenClaw's `detectOpenAICompletionsCompat` (design D5, research r39). */
class CompatDefaultsTest {
    private fun defaults(provider: String, base: String, model: String = "m", reasoning: Boolean = false) =
        CompatDefaults.defaults(provider, base.toHttpUrl(), model, reasoning)

    @Test
    fun openAisOwnEndpointGetsEveryOpenAiFeature() {
        val flags = defaults("openai", "https://api.openai.com/v1", "gpt-5.5", reasoning = true)

        assertEquals("max_completion_tokens", flags.maxTokensField)
        assertEquals(true, flags.supportsStore)
        assertEquals(true, flags.supportsDeveloperRole)
        assertEquals(true, flags.supportsStrictMode)
        assertEquals(true, flags.supportsUsageInStreaming)
        assertEquals(true, flags.supportsJsonSchemaResponseFormat)
        assertEquals("openai", flags.thinkingFormat)
        assertFalse(defaults("openai", "https://api.openai.com/v1", "gpt-3.5-turbo").supportsJsonSchemaResponseFormat!!)
    }

    @Test
    fun deepSeekAndXiaomiNeedTheirReasoningTextBack() {
        for (base in listOf("https://api.deepseek.com", "https://token-plan-sgp.xiaomimimo.com/v1")) {
            val flags = defaults("any", base)
            assertEquals("deepseek", flags.thinkingFormat)
            assertEquals(true, flags.requiresReasoningContentOnAssistantMessages)
            assertEquals(false, flags.supportsStore)
            assertEquals(false, flags.supportsUsageInStreaming)
        }
    }

    @Test
    fun theProvidersThatWantMaxTokensGetIt() {
        val maxTokens = listOf(
            defaults("zai", "https://api.z.ai/api/paas/v4"),
            defaults("moonshot", "https://api.moonshot.ai/v1"),
            defaults("together", "https://api.together.xyz/v1"),
            defaults("mistral", "https://api.mistral.ai/v1"),
            defaults("chutes", "https://llm.chutes.ai/v1"),
            defaults("novita", "https://api.novita.ai/openai/v1"),
            defaults("qwen", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"),
        )
        assertTrue(maxTokens.all { it.maxTokensField == "max_tokens" })
        assertEquals("max_completion_tokens", defaults("qwen", "https://coding-intl.dashscope.aliyuncs.com/v1").maxTokensField)
        assertEquals("zai", defaults("zai", "https://open.bigmodel.cn/api/paas/v4").thinkingFormat)
        assertEquals("together", defaults("together", "https://api.together.xyz/v1").thinkingFormat)
        assertEquals(true, defaults("moonshot", "https://api.moonshot.cn/v1").supportsUsageInStreaming)
    }

    @Test
    fun openRouterUsesItsReasoningFormatAndAnthropicCacheMarkersForClaudeAndGemini() {
        val claude = defaults("openrouter", "https://openrouter.ai/api/v1", "anthropic/claude-sonnet-5")

        assertEquals("openrouter", claude.thinkingFormat)
        assertEquals(true, claude.supportsStrictMode)
        assertEquals("anthropic", claude.cacheControlFormat)
        assertEquals("anthropic", defaults("openrouter", "https://openrouter.ai/api/v1", "google/gemini-3.8-flash").cacheControlFormat)
        assertNull(defaults("openrouter", "https://openrouter.ai/api/v1", "openai/gpt-5.5").cacheControlFormat)
        assertEquals("anthropic", defaults("qwen", "https://coding-intl.dashscope.aliyuncs.com/v1").cacheControlFormat)
    }

    @Test
    fun localServersReportUsageInTheirStreamsAndAPublicProxyDoesNot() {
        // A LAN address counts as local (Kestrel's Hosts.isLocal), so a provider outside OpenClaw's streaming-usage list gets usage too.
        assertEquals(true, defaults("litellm", "http://192.168.1.20:4000/v1").supportsUsageInStreaming)
        assertEquals(true, defaults("vllm", "http://192.168.1.20:8000/v1").supportsUsageInStreaming)
        assertEquals(true, defaults("litellm", "http://127.0.0.1:4000/v1").supportsUsageInStreaming)
        assertEquals(false, defaults("litellm", "https://litellm.example.com/v1").supportsUsageInStreaming)
        assertEquals("local", CompatDefaults.endpointClass("http://10.0.0.5:8080/v1".toHttpUrl()))
    }

    @Test
    fun copilotAndAzureAreNotOpenAisOwnEndpoint() {
        val copilot = defaults("github-copilot", "https://api.individual.githubcopilot.com")
        val azure = defaults("microsoft-foundry", "https://kestrel.openai.azure.com/openai/v1")

        assertEquals("github-copilot-native", CompatDefaults.endpointClass("https://api.individual.githubcopilot.com".toHttpUrl()))
        assertEquals(false, copilot.supportsStrictMode)
        assertEquals(false, copilot.supportsDeveloperRole)
        assertEquals(true, azure.supportsStore)
        assertEquals(false, azure.supportsDeveloperRole)
    }

    @Test
    fun theModelsFlagsWinAndCopilotsFlagsReplaceThem() {
        val model = ModelEntry("m", "M", compat = CompatFlags(maxTokensField = "max_tokens", supportsUsageInStreaming = true))
        val target = target("openai", RequestStyle.CHAT_COMPLETIONS, "https://api.openai.com/v1", model)

        assertEquals("max_tokens", CompatDefaults.resolve(target).maxTokensField)
        assertEquals(true, CompatDefaults.resolve(target).supportsTools)
        val replaced = CompatDefaults.resolve(target.copy(options = StyleOptions(compat = CompatFlags(supportsUsageInStreaming = false))))
        assertEquals("max_completion_tokens", replaced.maxTokensField)
        assertEquals(false, replaced.supportsUsageInStreaming)
    }
}
