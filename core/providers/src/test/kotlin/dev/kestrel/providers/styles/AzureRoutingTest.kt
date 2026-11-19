package dev.kestrel.providers.styles

import dev.kestrel.providers.chat.RequestStyle
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AzureRoutingTest {
    @Test
    fun theDeploymentsNameChoosesTheApi() {
        assertEquals(RequestStyle.ANTHROPIC, AzureRouting.styleFor("claude-opus-5"))
        assertEquals(RequestStyle.ANTHROPIC, AzureRouting.styleFor("Claude-Opus-5"))
        // OpenClaw's isAnthropicFoundryDeployment: the name must start with `claude`.
        assertEquals(RequestStyle.CHAT_COMPLETIONS, AzureRouting.styleFor("my-claude-deployment"))
        for (name in listOf("gpt-6-luna", "o1-mini", "o3", "o4-mini", "deepseek-v4-pro", "computer-use-preview")) {
            assertEquals(name, RequestStyle.RESPONSES, AzureRouting.styleFor(name))
        }
        assertEquals(RequestStyle.CHAT_COMPLETIONS, AzureRouting.styleFor("DeepSeek-R1"))
        assertEquals(RequestStyle.CHAT_COMPLETIONS, AzureRouting.styleFor("grok-4.5"))
    }

    @Test
    fun anEndpointIsReducedToItsOriginOnAnAzureHost() {
        val root = "https://kestrel.openai.azure.com/".toHttpUrl()
        assertEquals(root, AzureRouting.normalise("https://kestrel.openai.azure.com"))
        assertEquals(root, AzureRouting.normalise(" https://kestrel.openai.azure.com/openai/v1/ "))
        assertEquals(root, AzureRouting.normalise("https://kestrel.openai.azure.com/openai/deployments/gpt-6-luna/chat/completions?api-version=2024-10-21"))
        assertEquals("https://kestrel.services.ai.azure.com/".toHttpUrl(), AzureRouting.normalise("https://kestrel.services.ai.azure.com/anthropic"))
    }

    @Test
    fun anAddressThatIsNotAnAzureEndpointIsRefused() {
        assertNull(AzureRouting.normalise("http://kestrel.openai.azure.com"))
        assertNull(AzureRouting.normalise("https://kestrel.openai.azure.com.evil.example"))
        assertNull(AzureRouting.normalise("https://user:pass@kestrel.openai.azure.com"))
        assertNull(AzureRouting.normalise("https://kestrel.openai.azure.com/other/path"))
        assertNull(AzureRouting.normalise("https://kestrel.openai.azure.com:8443/openai"))
        assertNull(AzureRouting.normalise("not an address"))
    }
}
