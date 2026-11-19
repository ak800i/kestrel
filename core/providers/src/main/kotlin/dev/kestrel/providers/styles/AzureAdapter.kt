package dev.kestrel.providers.styles

import dev.kestrel.providers.chat.ChatAdapter
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.ChatResponse
import dev.kestrel.providers.chat.ModelTarget
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.http.ProviderHttp

/**
 * Azure OpenAI and Microsoft Foundry (spec 10.3, design D4, research r36). [ModelTarget.baseUrl] is the owner's endpoint
 * and [ModelTarget.sentModelId] the deployment's name, which goes into the request as the model. The wire is
 * [dev.kestrel.providers.chat.StyleOptions.wire] when the owner chose another API on the profile page, else
 * [AzureRouting.styleFor]: Anthropic Messages at `<endpoint>/anthropic/v1/messages`, or Responses or Chat Completions at
 * `<endpoint>/openai/v1`. The key's header (`api-key`, or `x-api-key` for Claude) comes with the target's RequestAuth.
 * A Chat Completions deployment without its own `maxTokensField` gets `max_completion_tokens` only when its name starts
 * with `gpt-5` or a later GPT, or with `o` and a digit, and `max_tokens` otherwise: OpenClaw's
 * `requiresFoundryMaxCompletionTokens` (`extensions/microsoft-foundry/shared.ts`), widened to OpenAI's later reasoning models.
 * A GPT-6 deployment on Responses gets OpenClaw's generic efforts, not its GPT-6 list, as OpenClaw's
 * `resolveOpenAIModelReasoningEfforts` does for its `azure-openai-responses` API.
 */
internal class AzureAdapter(http: ProviderHttp) : ChatAdapter {
    private val chat = ChatCompletionsAdapter(http)
    private val responses = ResponsesAdapter(http, azure = true)
    private val anthropic = AnthropicAdapter(http)

    override suspend fun complete(request: ChatRequest, target: ModelTarget): ChatResponse {
        val root = AzureRouting.normalise(target.baseUrl.toString()) ?: target.baseUrl
        return when (target.options.wire ?: AzureRouting.styleFor(target.sentModelId)) {
            RequestStyle.ANTHROPIC -> anthropic.complete(
                request,
                target.copy(style = RequestStyle.ANTHROPIC, baseUrl = root.newBuilder().addPathSegment("anthropic").build(), options = target.options.copy(messagesPath = "/v1/messages")),
            )
            RequestStyle.RESPONSES -> responses.complete(
                request,
                target.copy(style = RequestStyle.RESPONSES, baseUrl = root.newBuilder().addPathSegments("openai/v1").build()),
            )
            else -> {
                val own = target.options.compat ?: target.model.compat
                val field = own.maxTokensField
                    ?: if (MAX_COMPLETION_TOKENS.containsMatchIn(target.sentModelId.trim().lowercase())) "max_completion_tokens" else "max_tokens"
                chat.complete(
                    request,
                    target.copy(
                        style = RequestStyle.CHAT_COMPLETIONS,
                        baseUrl = root.newBuilder().addPathSegments("openai/v1").build(),
                        options = target.options.copy(compat = own.copy(maxTokensField = field)),
                    ),
                )
            }
        }
    }

    private companion object {
        val MAX_COMPLETION_TOKENS = Regex("^(?:gpt-(?:[5-9]|\\d{2})|o\\d)")
    }
}
