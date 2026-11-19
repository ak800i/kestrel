package dev.kestrel.providers.styles

import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.ChatAdapter
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.ChatRequests
import dev.kestrel.providers.chat.ChatResponse
import dev.kestrel.providers.chat.ImageType
import dev.kestrel.providers.chat.InvalidKind
import dev.kestrel.providers.chat.ModelTarget
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.chat.ReasoningState
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.chat.StopReason
import dev.kestrel.providers.chat.ToolChoice
import dev.kestrel.providers.chat.Usage
import dev.kestrel.providers.http.ClockSkew
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.styles.Wire.arr
import dev.kestrel.providers.styles.Wire.long
import dev.kestrel.providers.styles.Wire.obj
import dev.kestrel.providers.styles.Wire.objects
import dev.kestrel.providers.styles.Wire.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Amazon Bedrock's Converse style (spec 10.3, design D4, D6, D9 and D16, research r34): `POST
 * <runtime>/model/<model ID>/converse`, not streamed, because ConverseStream is a binary AWS event stream. Converse wants
 * alternating roles, so tool results and the next user message become one user turn; it refuses `toolUse` and
 * `toolResult` blocks without a `toolConfig`, so a request without tools sends its tool history as text. The network log
 * records `/model/{model}/converse`, because a model ID can be an inference-profile ARN with the AWS account ID. AWS's
 * refusals go through [BedrockErrors]: its token errors (403 with `ExpiredTokenException`, `UnrecognizedClientException` or
 * `InvalidSignatureException`) are the credential's own refusal (`rejected`), new keys for IAM keys and a new key for an
 * API key, and a refused signature with a clock skew of more than 5 minutes is signed once more with AWS's time, which
 * [skew] keeps for every later request. The
 * history's tool-use IDs are rewritten on every request to Converse's pattern (`[a-zA-Z0-9_.:-]`, 1 to 64 characters) by
 * [Wire.toolUseId], so that a call and its result still pair. A Claude model's `reasoningContent` blocks (signed
 * reasoning text or redacted content) go back unchanged, in their order, to the same provider and model, as in OpenClaw's
 * `convertMessages`
 * (`extensions/amazon-bedrock/stream.runtime.ts`); other models keep no reasoning state (design D5). A Claude model that
 * thinks by default ([AnthropicModels.thinksByDefault]) gets a required tool choice as `auto` (except Claude Haiku 5.5), as
 * in OpenClaw's `normalizeAdaptiveClaudeToolChoice`, and no temperature, which every Claude 5 model refuses. Without tools
 * the history becomes text, so reasoning blocks are neither kept from nor sent in such a request (Anthropic checks the
 * prefix that produced them).
 */
internal class BedrockAdapter(private val http: ProviderHttp, private val skew: ClockSkew = ClockSkew.SHARED) : ChatAdapter {
    override suspend fun complete(request: ChatRequest, target: ModelTarget): ChatResponse {
        ChatRequests.check(request)
        val url = target.baseUrl.newBuilder()
            .addPathSegment("model")
            .addEncodedPathSegment(SigV4Auth.encode(target.sentModelId))
            .addPathSegment("converse")
            .build()
        val reply = BedrockErrors.send(http, Wire.post(url, body(request, target), target, request, stream = false, logPath = LOG_PATH), target.auth, skew)
        return read(Wire.parse(reply.text()), request, target)
    }

    private fun body(request: ChatRequest, target: ModelTarget): JsonObject = buildJsonObject {
        val tools = sendsTools(request, target)
        val thinks = AnthropicModels.thinksByDefault(target.sentModelId)
        val sampling = !thinks && !AnthropicModels.defaultSamplingOnly(target.sentModelId) && target.model.compat.supportsTemperature != false
        put("messages", messages(request, target, tools))
        if (request.system.isNotBlank()) putJsonArray("system") { addJsonObject { put("text", request.system) } }
        putJsonObject("inferenceConfig") {
            put("maxTokens", request.maxOutputTokens)
            if (request.temperature != null && sampling) put("temperature", request.temperature)
        }
        if (tools) putJsonObject("toolConfig") {
            putJsonArray("tools") {
                for (tool in request.tools) addJsonObject {
                    putJsonObject("toolSpec") {
                        put("name", tool.name)
                        put("description", tool.description)
                        putJsonObject("inputSchema") { put("json", tool.parameters) }
                    }
                }
            }
            // `auto` is Converse's default, so only a required choice is sent, as OpenClaw's convertToolConfig does.
            if (request.toolChoice == ToolChoice.REQUIRED) putJsonObject("toolChoice") { putJsonObject(if (thinks && !AnthropicModels.keepsForcedToolChoice(target.sentModelId)) "auto" else "any") {} }
        }
    }

    /** Adjacent turns of one role become one turn, so that the roles alternate. */
    private fun messages(request: ChatRequest, target: ModelTarget, tools: Boolean): JsonArray {
        val turns = mutableListOf<Pair<String, MutableList<JsonObject>>>()
        fun add(role: String, blocks: List<JsonObject>) {
            if (blocks.isEmpty()) return
            val last = turns.lastOrNull()
            if (last != null && last.first == role) last.second += blocks else turns += role to blocks.toMutableList()
        }
        val statusAllowed = Regex("anthropic|claude|nova").containsMatchIn(target.sentModelId.lowercase())
        for (message in request.messages) {
            when (message) {
                is ChatMessage.User -> add("user", message.parts.mapNotNull { part ->
                    when (part) {
                        is Part.Text -> part.text.takeIf { it.isNotBlank() }?.let(::text)
                        is Part.Image -> buildJsonObject {
                            putJsonObject("image") {
                                put("format", if (part.type == ImageType.PNG) "png" else "jpeg")
                                putJsonObject("source") { put("bytes", Wire.base64(part.bytes)) }
                            }
                        }
                    }
                })
                is ChatMessage.Assistant -> add("assistant", message.parts.mapNotNull { part ->
                    when (part) {
                        is AssistantPart.Text -> part.text.takeIf { it.isNotBlank() }?.let(::text)
                        is AssistantPart.ToolCall -> if (tools) buildJsonObject {
                            putJsonObject("toolUse") {
                                put("toolUseId", Wire.toolUseId(part.id, Wire.CONVERSE_ID_CHARS))
                                put("name", part.name)
                                put("input", part.arguments)
                            }
                        } else text(Wire.callText(part))
                        is AssistantPart.Reasoning -> part.state
                            // Without tools the history becomes text, which changes the prefix that Anthropic checks for a replayed block.
                            .takeIf { tools && it.style == RequestStyle.BEDROCK && it.providerId == target.providerId && it.modelId == target.sentModelId }
                            ?.payload as? JsonObject
                    }
                })
                is ChatMessage.ToolResult -> add("user", listOf(if (tools) buildJsonObject {
                    putJsonObject("toolResult") {
                        put("toolUseId", Wire.toolUseId(message.callId, Wire.CONVERSE_ID_CHARS))
                        // Converse refuses a blank text block; OpenClaw's createBedrockToolResultContent sends this instead.
                        putJsonArray("content") { addJsonObject { put("text", message.text.ifBlank { "(no output)" }) } }
                        if (message.failed && statusAllowed) put("status", "error")
                    }
                } else text(Wire.resultText(message))))
            }
        }
        return buildJsonArray {
            for ((role, blocks) in turns) addJsonObject {
                put("role", role)
                put("content", JsonArray(blocks))
            }
        }
    }

    /** Whether the request sends its tools: Converse has no "none" choice and refuses tool blocks without a toolConfig. */
    private fun sendsTools(request: ChatRequest, target: ModelTarget): Boolean =
        request.tools.isNotEmpty() && request.toolChoice != ToolChoice.NONE && target.model.compat.supportsTools != false

    private fun text(value: String): JsonObject = buildJsonObject { put("text", value) }

    private fun read(body: JsonObject, request: ChatRequest, target: ModelTarget): ChatResponse {
        val message = body.obj("output")?.obj("message") ?: throw ProviderException(ProviderError.Malformed)
        val reply = ReplyBuilder(request)
        val claude = AnthropicModels.identity(target.sentModelId) != null
        var reasoningChars = 0
        for (block in message.arr("content").objects()) {
            block.str("text")?.let(reply::text)
            block.obj("toolUse")?.let { use -> reply.call(use.str("toolUseId").orEmpty(), use.str("name").orEmpty(), use["input"]) }
            block.obj("reasoningContent")?.let { reasoning ->
                reasoningChars += reasoning.obj("reasoningText")?.str("text")?.length ?: 0
                // A block from a request without tools would go back later after another prefix (Anthropic's prefix check).
                if (claude && sendsTools(request, target)) reply.reasoning(ReasoningState(RequestStyle.BEDROCK, target.providerId, target.sentModelId, block))
            }
        }
        val stop = when (body.str("stopReason")) {
            null, "end_turn", "stop_sequence" -> StopReason.END
            "tool_use" -> StopReason.TOOL_CALL
            "max_tokens", "model_context_window_exceeded" -> StopReason.MAX_TOKENS
            "guardrail_intervened", "content_filtered" -> StopReason.FILTERED
            "malformed_model_output", "malformed_tool_use" -> {
                reply.invalid(InvalidKind.BAD_JSON, "")
                StopReason.OTHER
            }
            else -> StopReason.OTHER
        }
        val usage = body.obj("usage")?.let {
            Usage(
                input = it.long("inputTokens") ?: 0,
                output = it.long("outputTokens") ?: 0,
                cacheRead = it.long("cacheReadInputTokens") ?: 0,
                cacheWrite = it.long("cacheWriteInputTokens") ?: 0,
            )
        } ?: Wire.estimate(request, reply.replyChars, reasoningChars, target.model.reasoning)
        return reply.response(stop, usage, target.sentModelId)
    }

    companion object {
        const val LOG_PATH = "/model/{model}/converse"
    }
}
