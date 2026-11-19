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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * OpenAI's Responses style (spec 10.3, design D4, D5, D6 and D17), used by OpenAI's API keys, the ChatGPT plan, xAI,
 * Meta, Copilot's GPT models, Mantle's GPT models and Azure's GPT deployments. Requests send `store: false` and ask for
 * the encrypted reasoning, which goes back unchanged (without its `id`) to the same provider and model. The reply comes
 * from the stream's `response.completed` (its `output_item.done` items by their `output_index` when the final event has no
 * output, or else its text deltas); `response.incomplete` is a reply that stopped early, and a final response whose status
 * is `failed` fails with its error. A 400 with `invalid_encrypted_content` or `thinking_signature_invalid` is sent once
 * more without reasoning items, when the request sent any.
 *
 * With [dev.kestrel.providers.chat.StyleOptions.chatgptPlan] the request follows the ChatGPT plan's preview limits (OpenAI's
 * "Preview limitations", research r46): `instructions`, `input` as an array, `store: false`, `stream: true`, none of the
 * fields that the preview rejects, and Kestrel's function tools in one namespace `kestrel`. Its error codes map as design
 * D17 says, inside the stream and before it, and every 429 is QuotaOrPolicy, because a plan's limit does not end within a
 * retry.
 *
 * [azure] is set by the Azure adapter for an Azure OpenAI endpoint: Azure OpenAI's Responses API gives a GPT-6
 * deployment OpenClaw's generic efforts ([Wire.effort]'s `azureResponses`); Microsoft Foundry's other hosts do not set it.
 */
internal class ResponsesAdapter(private val http: ProviderHttp, private val azure: Boolean = false) : ChatAdapter {
    override suspend fun complete(request: ChatRequest, target: ModelTarget): ChatResponse {
        ChatRequests.check(request)
        return try {
            send(request, target)
        } catch (e: ProviderException) {
            val error = e.error
            val stale = error is ProviderError.BadRequest && error.status == 400 && error.code in STALE_REASONING
            if (stale && sendsReasoning(request, target)) send(withoutReasoning(request), target) else throw e
        }
    }

    private suspend fun send(request: ChatRequest, target: ModelTarget): ChatResponse {
        val plan = target.options.chatgptPlan
        val stream = plan || target.options.stream != false
        val url = target.baseUrl.newBuilder().addPathSegment("responses").build()
        val gather = Gather(request, target)
        try {
            val post = Wire.post(url, body(request, target, stream), target, request, stream)
            if (stream) {
                http.stream(post, target.auth) { event ->
                    // OpenAI's SDK ignores a closing `data: [DONE]`, which some servers send after response.completed.
                    if (event.data.trim() != "[DONE]") gather.event(Wire.parse(event.data), event.event)
                }
            } else {
                gather.final(Wire.parse(http.send(post, target.auth).text()))
            }
        } catch (e: ProviderException) {
            if (!plan) throw e
            val error = e.error
            throw ProviderException(
                when (error) {
                    is ProviderError.RateLimited -> ProviderError.QuotaOrPolicy(429, error.code)
                    is ProviderError.QuotaOrPolicy -> planError(error.code, target) ?: error
                    is ProviderError.BadRequest -> planError(error.code, target) ?: error
                    else -> error
                },
            )
        }
        return gather.finish()
    }

    private fun body(request: ChatRequest, target: ModelTarget, stream: Boolean): JsonObject = buildJsonObject {
        val plan = target.options.chatgptPlan
        val compat = target.model.compat
        val tools = request.tools.isNotEmpty() && compat.supportsTools != false
        put("model", target.sentModelId)
        if (plan && request.system.isNotEmpty()) put("instructions", request.system)
        put("input", input(request, target, tools))
        if (tools) {
            val functions = buildJsonArray {
                for (tool in request.tools) addJsonObject {
                    put("type", "function")
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", tool.parameters)
                    if (compat.supportsStrictMode != false) put("strict", false)
                }
            }
            if (plan) {
                putJsonArray("tools") {
                    addJsonObject {
                        put("type", "namespace")
                        put("name", NAMESPACE)
                        put("description", "Kestrel's phone actions.")
                        put("tools", functions)
                    }
                }
            } else {
                put("tools", functions)
            }
            put("tool_choice", when (request.toolChoice) {
                ToolChoice.AUTO -> "auto"
                ToolChoice.REQUIRED -> "required"
                ToolChoice.NONE -> "none"
            })
            put("parallel_tool_calls", false)
        }
        val level = Wire.level(request, target.model, compat)
        val effort = level?.let { Wire.effort(it, target.model, compat, azureResponses = azure) }
        if (effort != null) putJsonObject("reasoning") { put("effort", effort) }
        if (!plan) {
            put("max_output_tokens", request.maxOutputTokens)
            if (request.temperature != null && compat.supportsTemperature != false) put("temperature", request.temperature)
            val cacheKey = request.cacheKey
            if (cacheKey != null && target.providerId == "openai" && compat.supportsPromptCacheKey != false) put("prompt_cache_key", cacheKey)
        }
        put("store", false)
        // OpenAI refuses this include for a model that does not reason ("Encrypted content is not supported with this model").
        if (target.model.reasoning || plan) putJsonArray("include") { add(JsonPrimitive("reasoning.encrypted_content")) }
        put("stream", stream)
    }

    private fun input(request: ChatRequest, target: ModelTarget, tools: Boolean): JsonArray = buildJsonArray {
        val plan = target.options.chatgptPlan
        if (!plan && request.system.isNotEmpty()) addJsonObject {
            put("role", "system")
            put("content", request.system)
        }
        for (message in request.messages) {
            when (message) {
                is ChatMessage.User -> addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        for (part in message.parts) addJsonObject {
                            when (part) {
                                is Part.Text -> {
                                    put("type", "input_text")
                                    put("text", part.text)
                                }
                                is Part.Image -> {
                                    put("type", "input_image")
                                    put("image_url", "data:${part.type.mime};base64,${Wire.base64(part.bytes)}")
                                }
                            }
                        }
                    }
                }
                is ChatMessage.Assistant -> assistant(message, target, tools)
                is ChatMessage.ToolResult -> if (tools) {
                    addJsonObject {
                        put("type", "function_call_output")
                        put("call_id", message.callId)
                        put("output", message.text)
                    }
                } else {
                    addJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            addJsonObject {
                                put("type", "input_text")
                                put("text", Wire.resultText(message))
                            }
                        }
                    }
                }
            }
        }
    }

    /** The assistant's items in their order: its reasoning items, its text as one message, and its function calls. */
    private fun JsonArrayBuilder.assistant(message: ChatMessage.Assistant, target: ModelTarget, tools: Boolean) {
        reasoningItems(message, target).forEach { add(it) }
        val text = Wire.assistantText(message, callsAsText = !tools)
        if (text.isNotEmpty()) addJsonObject {
            put("type", "message")
            put("role", "assistant")
            putJsonArray("content") {
                addJsonObject {
                    put("type", "output_text")
                    put("text", text)
                }
            }
        }
        if (tools) for (call in message.toolCalls) addJsonObject {
            put("type", "function_call")
            put("call_id", call.id)
            put("name", call.name)
            if (target.options.chatgptPlan) put("namespace", NAMESPACE)
            put("arguments", call.arguments.toString())
        }
    }

    /** The reasoning items that go back: only those of this style, provider and model. */
    private fun reasoningItems(message: ChatMessage.Assistant, target: ModelTarget): List<JsonObject> =
        message.parts.filterIsInstance<AssistantPart.Reasoning>().map { it.state }
            .filter { it.style == RequestStyle.RESPONSES && it.providerId == target.providerId && it.modelId == target.sentModelId }
            .mapNotNull { it.payload as? JsonObject }

    /** Whether the request sent any reasoning item, so that a resend without them would differ. */
    private fun sendsReasoning(request: ChatRequest, target: ModelTarget): Boolean =
        request.messages.any { it is ChatMessage.Assistant && reasoningItems(it, target).isNotEmpty() }

    private fun withoutReasoning(request: ChatRequest): ChatRequest =
        request.copy(messages = request.messages.map { message ->
            if (message is ChatMessage.Assistant) ChatMessage.Assistant(message.parts.filterNot { it is AssistantPart.Reasoning }) else message
        })

    /** One reply, gathered from the stream's events or from one complete response object. */
    private class Gather(private val request: ChatRequest, private val target: ModelTarget) {
        private val items = mutableListOf<Pair<Int, JsonObject>>()
        private val deltaText = StringBuilder()
        private var response: JsonObject? = null
        private var stop: StopReason? = null
        private var deltaReasoningChars = 0
        private val limit = GatherLimit()

        /** One event; a server that leaves out `type` names the event in the SSE `event:` line instead. */
        fun event(event: JsonObject, name: String? = null) {
            when (event.str("type") ?: name) {
                "response.output_item.done" -> event.obj("item")?.let { item ->
                    limit.add(item.toString().length)
                    items += (event.long("output_index")?.toInt() ?: Int.MAX_VALUE) to item
                }
                "response.output_text.delta" -> event.str("delta")?.let {
                    limit.add(it.length)
                    deltaText.append(it)
                }
                "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> event.str("delta")?.let {
                    limit.add(it.length)
                    deltaReasoningChars += it.length
                }
                "response.completed" -> final(event.obj("response") ?: JsonObject(emptyMap()))
                "response.incomplete" -> final(event.obj("response") ?: JsonObject(emptyMap()), incomplete = true)
                "response.failed" -> {
                    val error = event.obj("response")?.obj("error")
                    throw ProviderException(failure(error?.str("code")))
                }
                "error" -> throw ProviderException(failure(event.str("code") ?: event.obj("error")?.str("code")))
            }
        }

        /**
         * A final reply; an incomplete one keeps its output and usage and stops by its reason (OpenAI's "Models and
         * inference"), and a failed one fails with its error's code, or as Malformed without an error.
         */
        fun final(reply: JsonObject, incomplete: Boolean = reply.str("status") == "incomplete") {
            if (reply.str("status") == "failed") {
                val error = reply.obj("error") ?: throw ProviderException(ProviderError.Malformed)
                throw ProviderException(failure(error.str("code")))
            }
            response = reply
            stop = if (!incomplete) StopReason.END else when (reply.obj("incomplete_details")?.str("reason")) {
                "max_output_tokens" -> StopReason.MAX_TOKENS
                "content_filter" -> StopReason.FILTERED
                else -> StopReason.OTHER
            }
        }

        /** The plan's codes are mapped once, in [send]'s catch, which also sees the errors before the stream. */
        private fun failure(code: String?): ProviderError = Wire.streamError(null, code, target)

        fun finish(): ChatResponse {
            val reply = response ?: throw ProviderException(ProviderError.Malformed)
            // A final event without output: the `output_item.done` items by their `output_index`, else the text deltas.
            val output = reply.arr("output").objects().ifEmpty { items.sortedBy { it.first }.map { it.second } }
            val builder = ReplyBuilder(request)
            var summaryChars = 0
            for (item in output) {
                when (item.str("type")) {
                    "reasoning" -> {
                        if (item.str("encrypted_content") != null) {
                            builder.reasoning(ReasoningState(RequestStyle.RESPONSES, target.providerId, target.sentModelId, JsonObject(item - "id")))
                        }
                        summaryChars += (item.arr("summary").objects() + item.arr("content").objects()).sumOf { it.str("text")?.length ?: 0 }
                    }
                    "message" -> for (content in item.arr("content").objects()) {
                        content.str("text")?.let(builder::text)
                        content.str("refusal")?.let(builder::text)
                    }
                    "function_call" -> {
                        val namespace = item.str("namespace")
                        val name = item.str("name").orEmpty()
                        builder.call(item.str("call_id") ?: item.str("id").orEmpty(), if (namespace == null || namespace == NAMESPACE) name else "$namespace.$name", item.str("arguments").orEmpty())
                    }
                }
            }
            if (output.isEmpty()) builder.text(deltaText.toString())
            // The stream sends the reasoning both as deltas and in its items, so the estimate counts it once.
            val reasoningChars = if (deltaReasoningChars > 0) deltaReasoningChars else summaryChars
            val usage = reply.obj("usage")?.let(::usageOf) ?: Wire.estimate(request, builder.replyChars, reasoningChars, target.model.reasoning)
            return builder.response(stop ?: StopReason.END, usage, reply.str("model") ?: target.sentModelId)
        }

        /** Design D6, research r41. */
        private fun usageOf(usage: JsonObject): Usage {
            val input = usage.long("input_tokens") ?: 0
            val details = usage.obj("input_tokens_details")
            val cached = details?.long("cached_tokens") ?: 0
            val written = details?.long("cache_write_tokens") ?: 0
            return Usage(
                input = Wire.minus(input, cached, written),
                output = usage.long("output_tokens") ?: 0,
                cacheRead = cached,
                cacheWrite = written,
                reasoning = usage.obj("output_tokens_details")?.long("reasoning_tokens") ?: 0,
            )
        }
    }

    companion object {
        const val NAMESPACE = "kestrel"

        private val STALE_REASONING = setOf("invalid_encrypted_content", "thinking_signature_invalid")

        /** The ChatGPT plan's error codes (design D17; OpenAI's "Errors and recovery", research r46), or null for another code. */
        fun planError(code: String?, target: ModelTarget): ProviderError? = when (code) {
            "subscription_sharing_usage_limit_exceeded" -> ProviderError.QuotaOrPolicy(429, code)
            "subscription_sharing_user_not_eligible", "subscription_sharing_route_not_supported",
            "chatpass_v2_scope_not_authorized", "chatpass_v2_invalid_authorization_context" -> ProviderError.QuotaOrPolicy(403, code)
            "subscription_sharing_invalid_user" -> ProviderError.SignInExpired(target.providerId)
            "subscription_sharing_usage_unavailable", "subscription_sharing_user_unavailable" -> ProviderError.ServerError(503)
            "subscription_sharing_unsupported_capability" -> ProviderError.BadRequest(400, code)
            else -> null
        }
    }
}
