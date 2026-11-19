package dev.kestrel.providers.styles

import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.ChatAdapter
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.ChatRequests
import dev.kestrel.providers.chat.ChatResponse
import dev.kestrel.providers.chat.InvalidKind
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
import dev.kestrel.providers.styles.Wire.bool
import dev.kestrel.providers.styles.Wire.long
import dev.kestrel.providers.styles.Wire.obj
import dev.kestrel.providers.styles.Wire.objects
import dev.kestrel.providers.styles.Wire.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl

/**
 * Google's Gemini style (spec 10.3, design D3 to D7), for Gemini API keys and, through VertexAdapter, Gemini on Vertex.
 * It streams `streamGenerateContent?alt=sse` and gathers the stream. The model's content goes back unchanged, with every
 * `thoughtSignature` on its part, to the same provider and model, unless the reply held a call that Kestrel left out. An
 * assistant message that is rebuilt instead (after an escalation, for example) gets Google's placeholder
 * `skip_thought_signature_validator` on its first function call, for Gemini 3 and later, as OpenClaw does (research r38).
 * Tool results go back as `functionResponse` parts, all of a step together with the next screen in one user turn. JSON
 * actions set `responseMimeType` without a schema (design D7).
 */
internal class GeminiAdapter(private val http: ProviderHttp) : ChatAdapter {
    override suspend fun complete(request: ChatRequest, target: ModelTarget): ChatResponse {
        val stream = target.options.stream != false
        return complete(request, target, url(target.baseUrl, "models", target.sentModelId, stream), stream, logPath = null)
    }

    suspend fun complete(request: ChatRequest, target: ModelTarget, url: HttpUrl, stream: Boolean, logPath: String?): ChatResponse {
        ChatRequests.check(request)
        val gather = Gather(request, target)
        val post = Wire.post(url, body(request, target), target, request, stream, logPath = logPath)
        if (stream) {
            http.stream(post, target.auth) { event -> gather.chunk(Wire.parse(event.data)) }
        } else {
            gather.chunk(Wire.parse(http.send(post, target.auth).text()))
        }
        return gather.finish()
    }

    private fun body(request: ChatRequest, target: ModelTarget): JsonObject = buildJsonObject {
        val compat = target.model.compat
        val tools = request.tools.isNotEmpty() && compat.supportsTools != false
        if (request.system.isNotBlank()) putJsonObject("systemInstruction") {
            putJsonArray("parts") { addJsonObject { put("text", request.system) } }
        }
        put("contents", contents(request, target, tools))
        if (tools) {
            putJsonArray("tools") {
                addJsonObject {
                    putJsonArray("functionDeclarations") {
                        for (tool in request.tools) addJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            if ((tool.parameters["properties"] as? JsonObject)?.isNotEmpty() == true) put("parameters", tool.parameters)
                        }
                    }
                }
            }
            putJsonObject("toolConfig") {
                putJsonObject("functionCallingConfig") {
                    put("mode", when (request.toolChoice) {
                        ToolChoice.AUTO -> "AUTO"
                        ToolChoice.REQUIRED -> "ANY"
                        ToolChoice.NONE -> "NONE"
                    })
                }
            }
        }
        putJsonObject("generationConfig") {
            put("maxOutputTokens", request.maxOutputTokens)
            if (request.temperature != null && compat.supportsTemperature != false) put("temperature", request.temperature)
            Wire.level(request, target.model, compat)?.let { thinking(it, target.sentModelId) }?.let { put("thinkingConfig", it) }
            if (request.jsonSchema != null) put("responseMimeType", "application/json")
        }
    }

    private fun contents(request: ChatRequest, target: ModelTarget, tools: Boolean): JsonArray = buildJsonArray {
        val pending = mutableListOf<JsonObject>()
        fun flush() {
            if (pending.isEmpty()) return
            addJsonObject {
                put("role", "user")
                put("parts", JsonArray(pending.toList()))
            }
            pending.clear()
        }
        val placeholder = needsSignature(target.sentModelId)
        for (message in request.messages) {
            when (message) {
                is ChatMessage.User -> {
                    for (part in message.parts) pending += when (part) {
                        is Part.Text -> if (part.text.isBlank()) continue else buildJsonObject { put("text", part.text) }
                        is Part.Image -> buildJsonObject {
                            putJsonObject("inlineData") {
                                put("mimeType", part.type.mime)
                                put("data", Wire.base64(part.bytes))
                            }
                        }
                    }
                    flush()
                }
                is ChatMessage.ToolResult -> pending += if (tools) buildJsonObject {
                    putJsonObject("functionResponse") {
                        if (!message.callId.startsWith(SYNTHETIC_ID)) put("id", message.callId)
                        put("name", message.name)
                        putJsonObject("response") { put(if (message.failed) "error" else "output", message.text) }
                    }
                } else buildJsonObject { put("text", Wire.resultText(message)) }
                is ChatMessage.Assistant -> {
                    flush()
                    val parts = modelParts(message, target, tools, placeholder)
                    if (parts.isNotEmpty()) addJsonObject {
                        put("role", "model")
                        put("parts", parts)
                    }
                }
            }
        }
        flush()
    }

    /** The model's own parts when they belong to this provider and model, else parts rebuilt from the message. */
    private fun modelParts(message: ChatMessage.Assistant, target: ModelTarget, tools: Boolean, placeholder: Boolean): JsonArray {
        val own = message.parts.filterIsInstance<AssistantPart.Reasoning>().map { it.state }.firstOrNull {
            it.style == RequestStyle.GEMINI && it.providerId == target.providerId && it.modelId == target.sentModelId
        }
        val raw = (own?.payload as? JsonObject)?.arr("parts")
        if (tools && raw != null) return raw
        return buildJsonArray {
            val text = Wire.assistantText(message, callsAsText = !tools)
            if (text.isNotBlank()) addJsonObject { put("text", text) }
            if (tools) message.toolCalls.forEachIndexed { index, call ->
                addJsonObject {
                    putJsonObject("functionCall") {
                        if (!call.id.startsWith(SYNTHETIC_ID)) put("id", call.id)
                        put("name", call.name)
                        put("args", call.arguments)
                    }
                    if (placeholder && index == 0) put("thoughtSignature", SKIP_SIGNATURE)
                }
            }
        }
    }

    /** One reply, gathered from the chunks of a stream or from one complete body. */
    private class Gather(private val request: ChatRequest, private val target: ModelTarget) {
        private val raw = mutableListOf<MutableMap<String, JsonElement>>()
        private val text = StringBuilder()
        private var thoughtChars = 0
        private val calls = mutableListOf<JsonObject>()
        private var usage: JsonObject? = null
        private var modelId: String? = null
        private var finishReason: String? = null
        private val limit = GatherLimit()

        fun chunk(chunk: JsonObject) {
            chunk.obj("error")?.let { error ->
                throw ProviderException(Wire.streamError(error.long("code")?.toInt(), error.str("status"), target))
            }
            chunk.obj("usageMetadata")?.let { usage = it }
            chunk.str("modelVersion")?.let { modelId = it }
            chunk.obj("promptFeedback")?.str("blockReason")?.let { finishReason = "BLOCKED" }
            val candidate = chunk.arr("candidates").objects().firstOrNull() ?: return
            for (part in candidate.obj("content")?.arr("parts").objects()) {
                val signature = part["thoughtSignature"]
                // A signature is kept whole in the raw parts, so it counts too.
                (signature as? JsonPrimitive)?.content?.length?.let(limit::add)
                val call = part.obj("functionCall")
                val piece = part.str("text")
                when {
                    call != null -> {
                        limit.add(call["args"]?.toString()?.length ?: 0)
                        calls += call
                        raw += part.toMutableMap()
                    }
                    piece != null -> {
                        limit.add(piece.length)
                        val thought = part.bool("thought") == true
                        if (thought) thoughtChars += piece.length else text.append(piece)
                        val last = raw.lastOrNull()
                        val lastThought = (last?.get("thought") as? JsonPrimitive)?.content == "true"
                        if (last != null && last["text"] is JsonPrimitive && last["thoughtSignature"] == null && lastThought == thought) {
                            last["text"] = JsonPrimitive((last["text"] as JsonPrimitive).content + piece)
                            signature?.let { last["thoughtSignature"] = it }
                        } else {
                            raw += part.toMutableMap()
                        }
                    }
                    signature != null -> raw.lastOrNull()?.set("thoughtSignature", signature)
                }
            }
            candidate.str("finishReason")?.let { finishReason = it }
        }

        fun finish(): ChatResponse {
            val reason = finishReason ?: throw ProviderException(ProviderError.Malformed)
            val reply = ReplyBuilder(request)
            // The raw parts go back only when they hold no call that ReplyBuilder leaves out, because Gemini wants one
            // response for every call. Gemini's `args` is always an object, so only an unknown name drops a call.
            val names = request.tools.map { it.name }.toSet()
            if (raw.any { it["thoughtSignature"] != null } && calls.all { it.str("name").orEmpty() in names }) {
                reply.reasoning(ReasoningState(RequestStyle.GEMINI, target.providerId, target.sentModelId, buildJsonObject {
                    put("parts", JsonArray(raw.map { JsonObject(it) }))
                }))
            }
            reply.text(text.toString())
            val first = Wire.nextSyntheticNumber(request, SYNTHETIC_ID)
            calls.forEachIndexed { index, call ->
                reply.call(call.str("id") ?: "$SYNTHETIC_ID${first + index}", call.str("name").orEmpty(), call["args"])
            }
            val stop = when (reason) {
                "STOP" -> StopReason.END
                "MAX_TOKENS" -> StopReason.MAX_TOKENS
                "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY", "LANGUAGE", "BLOCKED" -> StopReason.FILTERED
                "MALFORMED_FUNCTION_CALL", "UNEXPECTED_TOOL_CALL" -> {
                    reply.invalid(InvalidKind.BAD_JSON, text.toString())
                    StopReason.OTHER
                }
                else -> StopReason.OTHER
            }
            val counted = usage?.let(::usageOf) ?: Wire.estimate(request, reply.replyChars, thoughtChars, target.model.reasoning)
            return reply.response(stop, counted, modelId ?: target.sentModelId)
        }

        /** Design D6: Gemini's prompt count holds the cached part, and thoughts are output besides the candidates. */
        private fun usageOf(usage: JsonObject): Usage {
            val cached = usage.long("cachedContentTokenCount") ?: 0
            val thoughts = usage.long("thoughtsTokenCount") ?: 0
            return Usage(
                input = Wire.minus(usage.long("promptTokenCount") ?: 0, cached) + (usage.long("toolUsePromptTokenCount") ?: 0),
                output = (usage.long("candidatesTokenCount") ?: 0) + thoughts,
                cacheRead = cached,
                reasoning = thoughts,
            )
        }
    }

    companion object {
        /** Google's documented value for history whose signature Kestrel does not have (research r38). */
        const val SKIP_SIGNATURE = "skip_thought_signature_validator"

        /**
         * The prefix of the IDs that Kestrel gives calls that came without one; they are never sent back. They are numbered
         * after the highest one in the request, so that a history stays free of repeated IDs for another style.
         */
        const val SYNTHETIC_ID = "gemini-call-"

        /** Gemini 3 and later refuse a call of the current turn without its signature (research r38 §3); OpenClaw's `requiresToolCallSignature`. */
        private fun needsSignature(modelId: String): Boolean =
            (Regex("gemini-(\\d+)").find(modelId.lowercase())?.groupValues?.get(1)?.toIntOrNull() ?: 0) >= 3

        /** `<base>/<collection>/<model>:streamGenerateContent?alt=sse`, or `:generateContent` without streaming. */
        fun url(base: HttpUrl, collection: String, modelId: String, stream: Boolean): HttpUrl =
            base.newBuilder()
                .addPathSegments(collection)
                .addPathSegment(modelId.removePrefix("models/") + if (stream) ":streamGenerateContent" else ":generateContent")
                .apply { if (stream) addQueryParameter("alt", "sse") }
                .build()

        /**
         * Ported from OpenClaw's thinking settings for Gemini (`packages/ai/src/providers/google-shared.ts` at the pinned commit): a
         * budget for Gemini 2.5, a level for Gemini 3 and later (only LOW and HIGH on Gemini 3 Pro; no MINIMAL on Gemini 4 or
         * on Gemini 3.7 Flash and later), and nothing for older models.
         */
        fun thinking(level: String, modelId: String): JsonObject? {
            val id = modelId.lowercase().substringAfterLast('/')
            val effort = when (level) {
                "xhigh", "max" -> "high"
                else -> level
            }
            val major = Regex("gemini-(\\d+)").find(id)?.groupValues?.get(1)?.toIntOrNull()
            if (major == null || major < 3) {
                val pro = "2.5-pro" in id
                if (!pro && "2.5-flash" !in id) return null
                val budget = when (effort) {
                    "minimal" -> if (!pro && "2.5-flash-lite" in id) 512 else 128
                    "low" -> 2048
                    "medium" -> 8192
                    else -> if (pro) 32768 else 24576
                }
                return buildJsonObject { put("thinkingBudget", budget) }
            }
            val low = effort == "minimal" || effort == "low"
            val value = when {
                Regex("gemini-3(?:\\.\\d+)?-pro").containsMatchIn(id) -> if (low) "LOW" else "HIGH"
                effort == "minimal" -> if (major >= 4 || (Regex("gemini-3\\.(\\d+)-flash").find(id)?.groupValues?.get(1)?.toIntOrNull() ?: 0) >= 7) "LOW" else "MINIMAL"
                else -> effort.uppercase()
            }
            return buildJsonObject { put("thinkingLevel", value) }
        }
    }
}
