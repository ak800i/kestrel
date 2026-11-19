package dev.kestrel.providers.styles

import dev.kestrel.providers.chat.RequestStyle
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** The style and API base of a model on Amazon Bedrock Mantle (design D4, research r34). */
data class MantleRoute(val style: RequestStyle, val baseUrl: HttpUrl)

/**
 * Bedrock Mantle serves each model through one of three APIs, as OpenClaw routes them (research r34): OpenAI's GPT models
 * (`openai.gpt-<n>…`) through Responses at `/openai/v1`, Claude through Anthropic Messages at `/anthropic`, and every other
 * model through Chat Completions at `/v1`, all at `https://bedrock-mantle.<region>.api.aws`.
 */
object MantleRouting {
    private val REGION = Regex("^[a-z0-9-]+$")
    private val GPT = Regex("^openai\\.gpt-\\d")

    /** Throws IllegalArgumentException for a region that is not a plain region name, because it becomes part of a host. */
    fun route(modelId: String, region: String): MantleRoute {
        require(REGION.matches(region)) { "A region must match ${REGION.pattern}" }
        val root = "https://bedrock-mantle.$region.api.aws"
        return when {
            GPT.containsMatchIn(modelId.lowercase()) -> MantleRoute(RequestStyle.RESPONSES, "$root/openai/v1".toHttpUrl())
            AnthropicModels.identity(modelId) != null || modelId.lowercase().startsWith("anthropic.") ->
                MantleRoute(RequestStyle.ANTHROPIC, "$root/anthropic".toHttpUrl())
            else -> MantleRoute(RequestStyle.CHAT_COMPLETIONS, "$root/v1".toHttpUrl())
        }
    }

    /** Mantle's model list, at `/v1/models` of the region's host whatever base the profile has (design D13). */
    fun modelsBase(region: String): HttpUrl = route("", region).baseUrl
}
