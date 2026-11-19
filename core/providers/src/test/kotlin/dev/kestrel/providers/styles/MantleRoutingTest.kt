package dev.kestrel.providers.styles

import dev.kestrel.providers.chat.RequestStyle
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MantleRoutingTest {
    @Test
    fun eachModelGoesToItsApi() {
        assertEquals(MantleRoute(RequestStyle.RESPONSES, "https://bedrock-mantle.us-east-1.api.aws/openai/v1".toHttpUrl()), MantleRouting.route("openai.gpt-6-luna", "us-east-1"))
        assertEquals(MantleRoute(RequestStyle.ANTHROPIC, "https://bedrock-mantle.eu-west-1.api.aws/anthropic".toHttpUrl()), MantleRouting.route("anthropic.claude-opus-5", "eu-west-1"))
        assertEquals(MantleRoute(RequestStyle.CHAT_COMPLETIONS, "https://bedrock-mantle.us-east-1.api.aws/v1".toHttpUrl()), MantleRouting.route("openai.gpt-oss-120b", "us-east-1"))
        assertEquals("https://bedrock-mantle.us-west-2.api.aws/v1".toHttpUrl(), MantleRouting.modelsBase("us-west-2"))
    }

    @Test
    fun aRegionThatIsNoPlainNameIsRefused() {
        val refused = runCatching { MantleRouting.route("qwen.qwen3-32b", "us-east-1.evil.example/") }

        assertTrue(refused.exceptionOrNull() is IllegalArgumentException)
    }
}
