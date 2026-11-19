package dev.kestrel.providers.styles

import dev.kestrel.providers.catalog.CompatFlags
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * OpenAI's Chat Completions style (spec 10.3, design D3 to D7), used by most providers of the catalog, by Copilot's
 * Gemini models, by Mantle's open models and by Azure's other deployments. It streams by default and gathers the stream
 * into one reply: text, tool-call deltas by index, the reasoning text fields, Gemini's thought signatures and the usage.
 * The model's compat flags, completed by [CompatDefaults], decide the request's quirks.
 */
internal class ChatCompletionsAdapter(private val http: ProviderHttp) : ChatAdapter {
    override suspend fun complete(request: ChatRequest, target: ModelTarget): ChatResponse {
        ChatRequests.check(request)
        val compat = CompatDefaults.resolve(target)
        val stream = target.options.stream ?: true
        val url = target.baseUrl.newBuilder().addPathSegments("chat/completions").build()
        val body = body(request, target, compat, stream)
        val gather = Gather(request, target)
        if (stream) {
            http.stream(Wire.post(url, body, target, request, stream = true), target.auth) { event ->
                if (event.data.trim() == "[DONE]") gather.done = true else gather.chunk(Wire.parse(event.data), "delta")
            }
        } else {
            gather.chunk(Wire.parse(http.send(Wire.post(url, body, target, request, stream = false), target.auth).text()), "message")
            gather.done = true
        }
        return gather.finish()
    }

    private fun body(request: ChatRequest, target: ModelTarget, compat: CompatFlags, stream: Boolean): JsonObject {
        val tools = request.tools.isNotEmpty() && compat.supportsTools != false
        val built = fields(request, target, compat, stream, tools)
        return when (openAiToolReasoning(target, tools)) {
            "none" -> JsonObject(built + ("reasoning_effort" to JsonPrimitive("none")))
            "omit" -> JsonObject(built - "reasoning_effort")
            else -> built
        }
    }

    /**
     * OpenClaw's `applyOpenAICompletionsToolReasoning` (`packages/ai/src/providers/openai-reasoning-effort.ts`) on OpenAI's
     * and Azure's own endpoints: with tools, GPT-5.6 needs `reasoning_effort: "none"`, and GPT-5.4 mini and GPT-5.5 refuse
     * the field. This applies after the compat-driven reasoning and whatever the compat flags say: GPT-5.6 with tools gets
     * `"none"` even when `supportsReasoningEffort` is false (the one exception to [Wire.effort]'s rule), because OpenAI's
     * Chat Completions refuses tools while GPT-5.6 reasons, as OpenClaw sends it.
     */
    private fun openAiToolReasoning(target: ModelTarget, tools: Boolean): String? {
        val host = target.baseUrl.host
        val ownEndpoint = CompatDefaults.endpointClass(target.baseUrl) in setOf("openai-public", "azure-openai") ||
            host.endsWith(".services.ai.azure.com") || host.endsWith(".cognitiveservices.azure.com")
        if (!tools || !ownEndpoint) return null
        val date = Regex("-\\d{4}-\\d{2}-\\d{2}$")
        val id = target.sentModelId.lowercase().replace(date, "")
        val name = target.model.name.lowercase().replace(date, "")
        return when {
            Regex("^gpt-5\\.6(?:-|$)").containsMatchIn(id) || Regex("^gpt-5\\.6(?:\\s|\\(|-|$)").containsMatchIn(name) -> "none"
            Regex("^gpt-5\\.4-mini(?:-|$)").containsMatchIn(id) || Regex("^gpt-5\\.5(?:-|$)").containsMatchIn(id) ||
                Regex("^gpt-5\\.5(?:\\s|\\(|-|$)").containsMatchIn(name) -> "omit"
            else -> null
        }
    }

    private fun fields(request: ChatRequest, target: ModelTarget, compat: CompatFlags, stream: Boolean, tools: Boolean): JsonObject = buildJsonObject {
        put("model", target.sentModelId)
        put("messages", messages(request, target, compat, tools))
        if (tools) {
            putJsonArray("tools") {
                for (tool in request.tools) addJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("parameters", tool.parameters)
                        if (compat.supportsStrictMode == true) put("strict", false)
                    }
                }
            }
            put("tool_choice", when (request.toolChoice) {
                ToolChoice.AUTO -> "auto"
                ToolChoice.REQUIRED -> "required"
                ToolChoice.NONE -> "none"
            })
            put("parallel_tool_calls", false)
        }
        put(compat.maxTokensField ?: "max_completion_tokens", request.maxOutputTokens)
        if (request.temperature != null && compat.supportsTemperature != false) put("temperature", request.temperature)
        reasoning(request, target, compat)
        val schema = request.jsonSchema
        if (schema != null && compat.supportsJsonSchemaResponseFormat == true) {
            putJsonObject("response_format") {
                put("type", "json_schema")
                putJsonObject("json_schema") {
                    put("name", "kestrel_action")
                    put("schema", schema)
                }
            }
        }
        if (compat.supportsStore == true) put("store", false)
        put("stream", stream)
        if (stream && compat.supportsUsageInStreaming == true) putJsonObject("stream_options") { put("include_usage", true) }
    }

    /** Design D5: the level goes into the field that the model's thinking format names. */
    private fun JsonObjectBuilder.reasoning(request: ChatRequest, target: ModelTarget, compat: CompatFlags) {
        val level = Wire.level(request, target.model, compat) ?: return
        val effort = Wire.effort(level, target.model, compat, chatCompletions = true)
        val scalar = effort != null && compat.supportsReasoningEffort == true
        when (compat.thinkingFormat) {
            "openrouter" -> {
                // OpenClaw's usesBinaryOpenRouterThinking: a model whose own compat declares no effort only turns thinking on.
                val own = target.model.compat
                if (own.supportsReasoningEffort == false || own.supportedReasoningEfforts?.isEmpty() == true) {
                    putJsonObject("reasoning") { put("enabled", true) }
                } else if (effort != null) {
                    putJsonObject("reasoning") { put("effort", effort) }
                }
            }
            "zai" -> putJsonObject("thinking") {
                put("type", "enabled")
                put("clear_thinking", false)
            }
            "qwen" -> put("enable_thinking", true)
            "qwen-chat-template" -> putJsonObject("chat_template_kwargs") {
                put("enable_thinking", true)
                if (effort != null && !compat.supportedReasoningEfforts.isNullOrEmpty()) put("reasoning_effort", effort)
            }
            "deepseek" -> {
                putJsonObject("thinking") { put("type", "enabled") }
                if (scalar) put("reasoning_effort", effort)
            }
            "together" -> {
                putJsonObject("reasoning") { put("enabled", true) }
                if (scalar) put("reasoning_effort", effort)
            }
            else -> if (scalar) put("reasoning_effort", effort)
        }
    }

    private fun messages(request: ChatRequest, target: ModelTarget, compat: CompatFlags, tools: Boolean): JsonArray = buildJsonArray {
        if (request.system.isNotEmpty()) addJsonObject {
            put("role", if (compat.supportsDeveloperRole == true && target.model.reasoning) "developer" else "system")
            if (compat.cacheControlFormat == "anthropic") {
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", request.system)
                        putJsonObject("cache_control") { put("type", "ephemeral") }
                    }
                }
            } else {
                put("content", request.system)
            }
        }
        val list = request.messages
        for ((index, message) in list.withIndex()) {
            when (message) {
                is ChatMessage.User -> add(user(message, compat))
                is ChatMessage.Assistant -> add(assistant(message, target, compat, tools))
                is ChatMessage.ToolResult -> {
                    if (tools) {
                        addJsonObject {
                            put("role", "tool")
                            put("tool_call_id", message.callId)
                            if (compat.requiresToolResultName == true) put("name", message.name)
                            put("content", message.text)
                        }
                        if (compat.requiresAssistantAfterToolResult == true && list.getOrNull(index + 1) is ChatMessage.User) addJsonObject {
                            put("role", "assistant")
                            put("content", "OK.")
                        }
                    } else {
                        addJsonObject {
                            put("role", "user")
                            put("content", Wire.resultText(message))
                        }
                    }
                }
            }
        }
    }

    private fun user(message: ChatMessage.User, compat: CompatFlags): JsonObject = buildJsonObject {
        put("role", "user")
        val texts = message.parts.filterIsInstance<Part.Text>()
        val images = message.parts.filterIsInstance<Part.Image>()
        if (compat.requiresStringContent == true || (images.isEmpty() && texts.size == 1)) {
            put("content", texts.joinToString("\n") { it.text })
        } else {
            putJsonArray("content") {
                for (part in message.parts) addJsonObject {
                    when (part) {
                        is Part.Text -> {
                            put("type", "text")
                            put("text", part.text)
                        }
                        is Part.Image -> {
                            put("type", "image_url")
                            putJsonObject("image_url") { put("url", "data:${part.type.mime};base64,${Wire.base64(part.bytes)}") }
                        }
                    }
                }
            }
        }
    }

    private fun assistant(message: ChatMessage.Assistant, target: ModelTarget, compat: CompatFlags, tools: Boolean): JsonObject = buildJsonObject {
        put("role", "assistant")
        val text = Wire.assistantText(message, callsAsText = !tools)
        val calls = if (tools) message.toolCalls else emptyList()
        val states = message.parts.filterIsInstance<AssistantPart.Reasoning>().map { it.state }
            .filter { it.style == RequestStyle.CHAT_COMPLETIONS && it.providerId == target.providerId }
        val signatures = states.filter { it.modelId == target.sentModelId }
            .mapNotNull { (it.payload as? JsonObject)?.obj("thoughtSignatures") }
            .flatMap { it.entries }.associate { it.key to it.value }
        when {
            text.isNotEmpty() -> put("content", text)
            calls.isEmpty() || compat.requiresStringContent == true -> put("content", "")
            else -> put("content", JsonNull)
        }
        if (calls.isNotEmpty()) putJsonArray("tool_calls") {
            for (call in calls) addJsonObject {
                put("id", call.id)
                put("type", "function")
                putJsonObject("function") {
                    put("name", call.name)
                    put("arguments", call.arguments.toString())
                }
                signatures[call.id]?.let { signature ->
                    putJsonObject("extra_content") { putJsonObject("google") { put("thought_signature", signature) } }
                }
            }
        }
        val reasoning = states.mapNotNull { it.payload as? JsonObject }.firstOrNull { it.str("text") != null }
            ?.takeIf { keepsReasoning(compat, target) }
        val required = compat.requiresReasoningContentOnAssistantMessages == true
        if (reasoning != null) {
            val field = reasoning.str("field") ?: "reasoning_content"
            // OpenClaw's sanitizeOpenRouterReasoningReplayFields sends OpenRouter's `reasoning_text` back as `reasoning`.
            val name = when {
                required -> "reasoning_content"
                compat.thinkingFormat == "openrouter" && field == "reasoning_text" -> "reasoning"
                else -> field
            }
            put(name, reasoning.str("text"))
        } else if (required) {
            put("reasoning_content", "")
        }
    }

    /** Design D5: the plain reasoning text goes back to the same provider when one of OpenClaw's rules asks for it. */
    private fun keepsReasoning(compat: CompatFlags, target: ModelTarget): Boolean {
        val id = target.sentModelId.trim().lowercase()
        // OpenClaw's shouldPreserveOpenRouterReasoningReplay: OpenRouter's Claude and Grok routes get no plain reasoning back.
        val openRouterSigned = target.providerId == "openrouter" && (id.startsWith("anthropic/") || id.startsWith("x-ai/"))
        return compat.requiresReasoningContentOnAssistantMessages == true ||
            compat.thinkingFormat == "deepseek" || compat.thinkingFormat == "zai" ||
            replaysReasoning(target.sentModelId) ||
            (target.model.reasoning && target.providerId != "openai" && !openRouterSigned)
    }

    /** One reply, gathered from the chunks of a stream or from one complete body. */
    private class Gather(private val request: ChatRequest, private val target: ModelTarget) {
        private class Call {
            var id = ""
            var name = ""
            val arguments = StringBuilder()
            var signature: String? = null
        }

        private val text = StringBuilder()
        private val reasoning = StringBuilder()
        private var reasoningField: String? = null
        private val calls = sortedMapOf<Int, Call>()
        private var finishReason: String? = null
        private var usage: JsonObject? = null
        private var modelId: String? = null
        private var lastKey = 0
        private val limit = GatherLimit()
        var done = false

        fun chunk(chunk: JsonObject, key: String) {
            chunk["error"]?.takeIf { it !is JsonNull }?.let { error ->
                val details = error as? JsonObject
                val status = details?.long("code")?.toInt() ?: details?.long("status")?.toInt()
                throw ProviderException(Wire.streamError(status, details?.str("code") ?: details?.str("type"), target))
            }
            chunk.str("model")?.let { modelId = it }
            chunk.obj("usage")?.let { usage = it }
            val choice = chunk.arr("choices").objects().firstOrNull() ?: return
            // OpenClaw: some compatible providers attach the usage to the choice instead of the chunk.
            choice.obj("usage")?.let { usage = it }
            choice.str("finish_reason")?.takeIf { it.isNotBlank() }?.let { finishReason = it }
            val delta = choice.obj(key) ?: return
            delta.str("content")?.let {
                limit.add(it.length)
                text.append(it)
            }
            // One field per delta: a server that fills two of them with the same text would double it.
            REASONING_FIELDS.firstOrNull { !delta.str(it).isNullOrEmpty() }?.let { field ->
                if (reasoningField == null) reasoningField = field
                val piece = delta.str(field).orEmpty()
                limit.add(piece.length)
                reasoning.append(piece)
            }
            delta.arr("tool_calls").objects().forEachIndexed { position, piece ->
                // The whole piece counts, so that pieces that only open calls with an ID or a name count too.
                limit.add(piece.toString().length)
                val id = piece.str("id")?.takeIf { it.isNotEmpty() }
                // Some servers send no `index`: a new `id`, or a later piece of the same delta, starts a new call, and the
                // first piece of a delta without either continues the last call.
                val key = piece.long("index")?.toInt()
                    ?: id?.let { known -> calls.entries.firstOrNull { it.value.id == known }?.key }
                    ?: if (id != null || calls.isEmpty() || position > 0) (calls.keys.maxOrNull()?.plus(1) ?: position) else lastKey
                lastKey = key
                val call = calls.getOrPut(key) { Call() }
                if (id != null && call.id.isEmpty()) call.id = id
                piece.obj("function")?.let { function ->
                    function.str("name")?.let { if (call.name.isEmpty()) call.name = it }
                    function.str("arguments")?.let { call.arguments.append(it) }
                }
                // OpenClaw's extractToolCallThoughtSignature reads these three places.
                (piece.obj("extra_content")?.obj("google")?.str("thought_signature")
                    ?: piece.obj("function")?.str("thought_signature")
                    ?: piece.str("thought_signature"))?.takeIf { it.isNotEmpty() }?.let { call.signature = it }
            }
        }

        fun finish(): ChatResponse {
            if (!done && finishReason == null) throw ProviderException(ProviderError.Malformed)
            val reply = ReplyBuilder(request)
            val field = reasoningField
            if (field != null) {
                reply.reasoning(ReasoningState(RequestStyle.CHAT_COMPLETIONS, target.providerId, target.sentModelId, buildJsonObject {
                    put("field", field)
                    put("text", reasoning.toString())
                }))
            }
            reply.text(text.toString())
            val signatures = linkedMapOf<String, String>()
            for (call in calls.values) {
                // ReplyBuilder makes up an ID for a call without one and may rename a repeated one: the signature goes
                // under the final ID, so that it is found when the call is replayed.
                val id = reply.call(call.id, call.name, call.arguments.toString()) ?: continue
                call.signature?.let { signatures[id] = it }
            }
            if (signatures.isNotEmpty()) {
                reply.reasoning(ReasoningState(RequestStyle.CHAT_COMPLETIONS, target.providerId, target.sentModelId, buildJsonObject {
                    putJsonObject("thoughtSignatures") { signatures.forEach { (id, signature) -> put(id, signature) } }
                }))
            }
            val stop = when (finishReason) {
                null, "stop" -> StopReason.END
                "tool_calls", "tool_call", "function_call" -> StopReason.TOOL_CALL
                "length" -> StopReason.MAX_TOKENS
                "content_filter" -> StopReason.FILTERED
                else -> StopReason.OTHER
            }
            val counted = usage?.let(::usageOf) ?: Wire.estimate(request, reply.replyChars, reasoning.length, target.model.reasoning)
            return reply.response(stop, counted, modelId ?: target.sentModelId)
        }

        /**
         * Design D6, research r41, with the aliases of OpenClaw's `normalizeUsage` (`src/agents/usage.ts`) that a Chat
         * Completions server sends: a top-level `cache_read_input_tokens` or `cached_tokens` (Moonshot's) for the cache
         * reads, a top-level `cache_creation_input_tokens` or `prompt_tokens_details.cache_creation_input_tokens` for the
         * writes, and a top-level `reasoning_tokens`. Only the OpenAI-style counts are subtracted from the prompt.
         */
        private fun usageOf(usage: JsonObject): Usage {
            val prompt = usage.long("prompt_tokens") ?: 0
            val details = usage.obj("prompt_tokens_details")
            val cached = usage.long("cache_read_input_tokens") ?: usage.long("cached_tokens") ?: details?.long("cached_tokens") ?: 0
            val written = usage.long("cache_creation_input_tokens") ?: details?.long("cache_write_tokens")
                ?: details?.long("cache_creation_input_tokens") ?: 0
            val readInPrompt = usage.long("cached_tokens") != null || details?.long("cached_tokens") != null
            val writtenInPrompt = details?.long("cache_write_tokens") != null || details?.long("cache_creation_input_tokens") != null
            return Usage(
                input = Wire.minus(prompt, if (readInPrompt) cached else 0, if (writtenInPrompt) written else 0),
                output = usage.long("completion_tokens") ?: 0,
                cacheRead = cached,
                cacheWrite = written,
                reasoning = usage.long("reasoning_tokens") ?: usage.obj("completion_tokens_details")?.long("reasoning_tokens") ?: 0,
            )
        }
    }

    companion object {
        private val REASONING_FIELDS = listOf("reasoning_content", "reasoning", "reasoning_text")

        /**
         * OpenClaw's list of models that need their reasoning text back (`openai-completions-reasoning-replay.ts` at the
         * pinned commit, research r38): the last path segment of the ID, also the parts around a `:`, with the tier
         * suffixes `-free`, `-paid` and `-trial` removed.
         */
        private val REPLAY_MODELS = setOf(
            "deepseek-v4-flash", "deepseek-v4-pro", "kimi-for-coding", "kimi-k2.5", "kimi-k2.6", "kimi-k2.7-code",
            "kimi-k2.7-code-highspeed", "kimi-k3", "kimi-k3.1-pro", "kimi-k3.1-pro-highspeed", "kimi-k2-thinking",
            "kimi-k2-thinking-turbo", "mimo-v2-pro", "mimo-v2-omni", "mimo-v2.5", "mimo-v2.5-pro", "mimo-v2.6-flash",
            "mimo-v2.6-pro", "mimo-v2.6-pro-ultraspeed",
        )

        fun replaysReasoning(modelId: String): Boolean {
            val last = modelId.trim().lowercase().split('/').lastOrNull { it.isNotEmpty() } ?: return false
            val colon = last.split(':').filter { it.isNotEmpty() }
            val candidates = listOf(last) + if (colon.size > 1) listOf(colon.first(), colon.last()) else emptyList()
            return candidates.any { candidate ->
                candidate in REPLAY_MODELS || listOf("-free", "-paid", "-trial").any { suffix ->
                    candidate.length > suffix.length && candidate.endsWith(suffix) && candidate.removeSuffix(suffix) in REPLAY_MODELS
                }
            }
        }
    }
}
