package dev.kestrel.providers.styles

import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.ChatAdapter
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.ChatRequests
import dev.kestrel.providers.chat.ChatResponse
import dev.kestrel.providers.chat.ModelTarget
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.chat.ReasoningState
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.chat.StopReason
import dev.kestrel.providers.chat.ToolChoice
import dev.kestrel.providers.chat.Usage
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.styles.Wire.arr
import dev.kestrel.providers.styles.Wire.long
import dev.kestrel.providers.styles.Wire.obj
import dev.kestrel.providers.styles.Wire.objects
import dev.kestrel.providers.styles.Wire.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl

/**
 * Anthropic's Messages style (spec 10.3, design D4 to D6), used by Anthropic, MiniMax, Synthetic, Vercel's gateway,
 * Copilot's Claude models, Mantle's Claude models, Claude on Vertex and Claude deployments on Foundry. It streams and
 * gathers the stream; the thinking and redacted-thinking blocks go back unchanged, in their order, to the same provider
 * and model. Adaptive thinking (`output_config.effort`) goes only to the Claude models that take it. While thinking is on,
 * and always for a model that thinks by default ([AnthropicModels.thinksByDefault]), a forced tool choice becomes `auto`
 * (except for Claude Haiku 5.5, [AnthropicModels.keepsForcedToolChoice]), as OpenClaw sends it, and no temperature is
 * sent. A route that strips thinking (Copilot's) keeps no thinking blocks, also for a model that thinks by default, as
 * OpenClaw's Copilot replay policy does. Every request rewrites the tool-use IDs of its history to Anthropic's pattern
 * ([Wire.toolUseId]), and a thinking block without a signature goes back as text. The end of the system prompt and
 * the last tool carry Anthropic's default cache marker (design D4). [complete] with `vertex` is the form of Claude on
 * Vertex (design D4, research r35): no streaming, `anthropic_version` in the body and the model in the URL.
 */
internal class AnthropicAdapter(private val http: ProviderHttp) : ChatAdapter {
    override suspend fun complete(request: ChatRequest, target: ModelTarget): ChatResponse {
        val url = target.baseUrl.newBuilder().addPathSegments(target.options.messagesPath.trim('/')).build()
        return complete(request, target, url, vertex = false, logPath = null)
    }

    suspend fun complete(request: ChatRequest, target: ModelTarget, url: HttpUrl, vertex: Boolean, logPath: String?): ChatResponse {
        ChatRequests.check(request)
        val stream = !vertex && target.options.stream != false
        val body = body(request, target, vertex, stream)
        val headers = if (vertex) emptyMap() else mapOf("anthropic-version" to VERSION)
        val gather = Gather(request, target)
        val post = Wire.post(url, body, target, request, stream, headers, logPath)
        if (stream) {
            http.stream(post, target.auth) { event ->
                // A proxy's closing `data: [DONE]` and anything after message_stop are not part of the reply.
                if (!gather.stopped && event.data.trim() != "[DONE]") gather.event(Wire.parse(event.data))
            }
        } else {
            gather.message(Wire.parse(http.send(post, target.auth).text()))
        }
        return gather.finish()
    }

    private fun body(request: ChatRequest, target: ModelTarget, vertex: Boolean, stream: Boolean): JsonObject = buildJsonObject {
        val strip = target.options.stripThinking
        val compat = target.model.compat
        val tools = request.tools.isNotEmpty() && compat.supportsTools != false
        val level = if (strip) null else Wire.level(request, target.model, compat)
        val thinks = level != null && AnthropicModels.adaptive(target.sentModelId)
        val thinking = thinks || AnthropicModels.thinksByDefault(target.sentModelId)
        if (vertex) put("anthropic_version", VERTEX_VERSION) else put("model", target.sentModelId)
        put("max_tokens", request.maxOutputTokens)
        if (request.system.isNotBlank()) putJsonArray("system") {
            addJsonObject {
                put("type", "text")
                put("text", request.system)
                putJsonObject("cache_control") { put("type", "ephemeral") }
            }
        }
        put("messages", messages(request, target, tools))
        if (tools) {
            putJsonArray("tools") {
                request.tools.forEachIndexed { index, tool ->
                    addJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("input_schema", tool.parameters)
                        if (index == request.tools.lastIndex) putJsonObject("cache_control") { put("type", "ephemeral") }
                    }
                }
            }
            putJsonObject("tool_choice") {
                when (request.toolChoice) {
                    ToolChoice.NONE -> put("type", "none")
                    ToolChoice.REQUIRED -> put("type", if (thinking && !AnthropicModels.keepsForcedToolChoice(target.sentModelId)) "auto" else "any")
                    ToolChoice.AUTO -> put("type", "auto")
                }
                if (request.toolChoice != ToolChoice.NONE) put("disable_parallel_tool_use", true)
            }
        }
        if (thinks) {
            putJsonObject("thinking") { put("type", "adaptive") }
            putJsonObject("output_config") { put("effort", AnthropicModels.effort(level, target.model.thinkingLevelMap)) }
        }
        val temperature = request.temperature
        if (temperature != null && !thinking && !AnthropicModels.defaultSamplingOnly(target.sentModelId) && compat.supportsTemperature != false) {
            put("temperature", temperature)
        }
        put("stream", stream)
    }

    /** Consecutive tool results and the user message after them become one user turn, results first. */
    private fun messages(request: ChatRequest, target: ModelTarget, tools: Boolean): JsonArray = buildJsonArray {
        val pending = mutableListOf<JsonObject>()
        fun flush() {
            if (pending.isEmpty()) return
            addJsonObject {
                put("role", "user")
                put("content", JsonArray(pending.toList()))
            }
            pending.clear()
        }
        for (message in request.messages) {
            when (message) {
                is ChatMessage.ToolResult -> pending += if (tools) buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", Wire.toolUseId(message.callId))
                    put("content", message.text)
                    if (message.failed) put("is_error", true)
                } else text(Wire.resultText(message))
                is ChatMessage.User -> {
                    for (part in message.parts) pending += when (part) {
                        is Part.Text -> if (part.text.isBlank()) continue else text(part.text)
                        is Part.Image -> buildJsonObject {
                            put("type", "image")
                            putJsonObject("source") {
                                put("type", "base64")
                                put("media_type", part.type.mime)
                                put("data", Wire.base64(part.bytes))
                            }
                        }
                    }
                    flush()
                }
                is ChatMessage.Assistant -> {
                    flush()
                    assistant(message, target, tools)
                }
            }
        }
        flush()
    }

    private fun JsonArrayBuilder.assistant(message: ChatMessage.Assistant, target: ModelTarget, tools: Boolean) {
        val keep = keepsThinking(target)
        val blocks = buildJsonArray {
            for (part in message.parts) {
                when (part) {
                    is AssistantPart.Reasoning -> {
                        val state = part.state
                        if (keep && state.style == RequestStyle.ANTHROPIC && state.providerId == target.providerId && state.modelId == target.sentModelId) {
                            (state.payload as? JsonObject)?.let(::replayed)?.let { add(it) }
                        }
                    }
                    is AssistantPart.Text -> if (part.text.isNotBlank()) add(text(part.text))
                    is AssistantPart.ToolCall -> if (tools) addJsonObject {
                        put("type", "tool_use")
                        put("id", Wire.toolUseId(part.id))
                        put("name", part.name)
                        put("input", part.arguments)
                    } else {
                        add(text(Wire.callText(part)))
                    }
                }
            }
        }
        // A message that would hold only thinking carries nothing for the next turn, so it is left out.
        if (blocks.all { (it as? JsonObject)?.str("type") in THINKING_BLOCKS }) return
        addJsonObject {
            put("role", "assistant")
            put("content", blocks)
        }
    }

    private fun text(value: String): JsonObject = buildJsonObject {
        put("type", "text")
        put("text", value)
    }

    /**
     * A kept block as it goes back. A thinking block without a signature goes back as text, or not at all when its
     * thinking is blank, as in OpenClaw's `convertMessages`, because a server that checks signatures refuses an empty one.
     */
    private fun replayed(block: JsonObject): JsonObject? {
        if (block.str("type") != "thinking" || !block.str("signature").isNullOrBlank()) return block
        return block.str("thinking")?.takeIf { it.isNotBlank() }?.let(::text)
    }

    /** One reply, from the stream's events or from one complete message. */
    private class Gather(private val request: ChatRequest, private val target: ModelTarget) {
        private class Block(val type: String, val start: JsonObject) {
            val text = StringBuilder()
            val signature = StringBuilder()
            val input = StringBuilder()
        }

        private val blocks = sortedMapOf<Int, Block>()
        private val limit = GatherLimit()
        private var usage: JsonObject? = null
        private var stopReason: String? = null
        private var modelId: String? = null
        var stopped = false
            private set
        private var message: JsonObject? = null

        fun event(event: JsonObject) {
            when (event.str("type")) {
                "message_start" -> event.obj("message")?.let { start ->
                    modelId = start.str("model")
                    usage = start.obj("usage")
                }
                "content_block_start" -> {
                    val block = event.obj("content_block") ?: return
                    val type = block.str("type").orEmpty()
                    // Anthropic starts a block empty, but a compatible provider may put its first text into the start.
                    val first = when (type) {
                        "text" -> block.str("text")
                        "thinking" -> block.str("thinking")
                        else -> null
                    }.orEmpty()
                    // The whole start counts, because a redacted_thinking or tool_use start carries its data or input.
                    limit.add(block.toString().length)
                    blocks[event.long("index")?.toInt() ?: blocks.size] = Block(type, block).apply { text.append(first) }
                }
                "content_block_delta" -> {
                    val block = blocks[event.long("index")?.toInt() ?: return] ?: return
                    val delta = event.obj("delta") ?: return
                    val (into, piece) = when (delta.str("type")) {
                        "text_delta" -> block.text to delta.str("text")
                        "thinking_delta" -> block.text to delta.str("thinking")
                        "signature_delta" -> block.signature to delta.str("signature")
                        "input_json_delta" -> block.input to delta.str("partial_json")
                        else -> return
                    }
                    val value = piece.orEmpty()
                    limit.add(value.length)
                    into.append(value)
                }
                "message_delta" -> {
                    event.obj("delta")?.str("stop_reason")?.let { stopReason = it }
                    // Anthropic: the usage of message_delta is cumulative and may also hold the input and cache counts.
                    event.obj("usage")?.let { delta -> usage = JsonObject((usage ?: JsonObject(emptyMap())) + delta.filterValues { it !is JsonNull }) }
                }
                "message_stop" -> stopped = true
                "error" -> {
                    val error = event.obj("error")
                    throw ProviderException(Wire.streamError(if (error?.str("type") == "overloaded_error") 529 else null, error?.str("type"), target))
                }
            }
        }

        fun message(body: JsonObject) {
            message = body
            stopped = true
        }

        fun finish(): ChatResponse {
            if (!stopped) throw ProviderException(ProviderError.Malformed)
            val whole = message
            if (whole != null && whole["content"] !is JsonArray) throw ProviderException(ProviderError.Malformed)
            val reply = ReplyBuilder(request)
            val keep = keepsThinking(target)
            var reasoningChars = 0
            // A streamed tool call's input goes to ReplyBuilder as the model's text, so that input that does not parse
            // reaches InvalidToolCall as it came.
            val contents: List<Pair<JsonObject, String?>> = if (whole != null) {
                whole.arr("content").objects().map { it to null }
            } else {
                blocks.values.map { block -> complete(block) to block.input.toString().takeIf { block.type == "tool_use" && it.isNotBlank() } }
            }
            for ((block, rawInput) in contents) {
                when (block.str("type")) {
                    "text" -> block.str("text")?.takeIf { it.isNotBlank() }?.let(reply::text)
                    "thinking", "redacted_thinking" -> {
                        reasoningChars += block.str("thinking")?.length ?: 0
                        if (keep) {
                            reply.reasoning(ReasoningState(RequestStyle.ANTHROPIC, target.providerId, target.sentModelId, block))
                        }
                    }
                    "tool_use" -> if (rawInput != null) {
                        reply.call(block.str("id").orEmpty(), block.str("name").orEmpty(), rawInput)
                    } else {
                        reply.call(block.str("id").orEmpty(), block.str("name").orEmpty(), block["input"])
                    }
                }
            }
            val counted = (whole?.obj("usage") ?: usage)?.takeIf { it.long("input_tokens") != null }?.let(::usageOf)
                ?: Wire.estimate(request, reply.replyChars, reasoningChars, target.model.reasoning)
            val stop = when (whole?.str("stop_reason") ?: stopReason) {
                null, "end_turn", "stop_sequence", "pause_turn" -> StopReason.END
                "tool_use" -> StopReason.TOOL_CALL
                "max_tokens", "model_context_window_exceeded" -> StopReason.MAX_TOKENS
                "refusal", "sensitive" -> StopReason.FILTERED
                else -> StopReason.OTHER
            }
            return reply.response(stop, counted, whole?.str("model") ?: modelId ?: target.sentModelId)
        }

        /**
         * A streamed block as Anthropic's complete block: text, thinking with its signature (none when it came without
         * one), or tool use with the input of its start (finish reads a streamed input as text).
         */
        private fun complete(block: Block): JsonObject = when (block.type) {
            "text" -> buildJsonObject {
                put("type", "text")
                put("text", block.text.toString())
            }
            "thinking" -> buildJsonObject {
                put("type", "thinking")
                put("thinking", block.text.toString())
                block.signature.toString().ifEmpty { block.start.str("signature").orEmpty() }.takeIf { it.isNotEmpty() }?.let { put("signature", it) }
            }
            "tool_use" -> buildJsonObject {
                put("type", "tool_use")
                put("id", block.start.str("id").orEmpty())
                put("name", block.start.str("name").orEmpty())
                put("input", block.start["input"] ?: JsonObject(emptyMap()))
            }
            else -> block.start
        }

        /** Design D6: Anthropic's input already excludes the cache. */
        private fun usageOf(usage: JsonObject): Usage = Usage(
            input = usage.long("input_tokens") ?: 0,
            output = usage.long("output_tokens") ?: 0,
            cacheRead = usage.long("cache_read_input_tokens") ?: 0,
            cacheWrite = usage.long("cache_creation_input_tokens") ?: 0,
        )
    }

    companion object {
        private val THINKING_BLOCKS = setOf("thinking", "redacted_thinking")

        /** Whether thinking blocks are kept and replayed: never on a route that strips thinking (Copilot's, design D12). */
        private fun keepsThinking(target: ModelTarget): Boolean = !target.options.stripThinking

        /** The version that OpenClaw's working paths send (design D4). */
        const val VERSION = "2023-06-01"

        const val VERTEX_VERSION = "vertex-2023-10-16"
    }
}
