package dev.kestrel.providers.styles

import dev.kestrel.providers.chat.ChatAdapter
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.ChatRequests
import dev.kestrel.providers.chat.ChatResponse
import dev.kestrel.providers.chat.ModelTarget
import dev.kestrel.providers.chat.Part
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
 * Ollama's native style (spec 10.3, design D4, D6, D7 and D11), for an Ollama server and Ollama Cloud: `POST
 * <origin>/api/chat` with `"stream": false`, because Ollama's stream is newline-delimited JSON, not server-sent events
 * (design D4). Images go as base64 strings beside the user's text, tool calls carry their arguments as JSON objects, and
 * JSON actions pass the action schema as `format`. Ollama keeps no reasoning state (design D5). Ollama has no tool
 * choice, so [ToolChoice.NONE] leaves out the tools and [ToolChoice.REQUIRED] acts as AUTO. A local server (not Ollama
 * Cloud) gets the model's window up to [MAX_CONTEXT] as its context and is asked to refuse a longer prompt instead of
 * cutting it, as OpenClaw does (`extensions/ollama/src/stream.runtime.ts`); Ollama Cloud keeps its own context.
 */
internal class OllamaAdapter(private val http: ProviderHttp) : ChatAdapter {
    override suspend fun complete(request: ChatRequest, target: ModelTarget): ChatResponse {
        ChatRequests.check(request)
        val url = origin(target.baseUrl).newBuilder().addPathSegments("api/chat").build()
        val reply = http.send(Wire.post(url, body(request, target), target, request, stream = false), target.auth)
        return read(Wire.parse(reply.text()), request, target)
    }

    private fun body(request: ChatRequest, target: ModelTarget): JsonObject = buildJsonObject {
        val compat = target.model.compat
        val tools = request.tools.isNotEmpty() && compat.supportsTools != false
        put("model", target.sentModelId)
        put("messages", messages(request, tools))
        put("stream", false)
        if (tools && request.toolChoice != ToolChoice.NONE) putJsonArray("tools") {
            for (tool in request.tools) addJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", tool.parameters)
                }
            }
        }
        request.jsonSchema?.let { put("format", it) }
        if (Wire.level(request, target.model, compat) != null) put("think", true)
        if (local(target)) {
            put("truncate", false)
            put("shift", false)
        }
        putJsonObject("options") {
            put("num_predict", request.maxOutputTokens)
            // OpenClaw caps only a local server's context, whose memory the window allocates; Ollama Cloud keeps its own.
            if (local(target)) target.model.contextWindow?.takeIf { it > 0 }?.let { put("num_ctx", minOf(it, MAX_CONTEXT)) }
            if (request.temperature != null && compat.supportsTemperature != false) put("temperature", request.temperature)
        }
    }

    /** OpenClaw's `localModel`: neither Ollama Cloud nor a cloud model that a local server passes on. */
    private fun local(target: ModelTarget): Boolean {
        val host = target.baseUrl.host
        val id = target.sentModelId.lowercase()
        return target.providerId != "ollama-cloud" && host != "ollama.com" && !host.endsWith(".ollama.com") &&
            !id.endsWith(":cloud") && !id.endsWith("-cloud")
    }

    private fun messages(request: ChatRequest, tools: Boolean): JsonArray = buildJsonArray {
        if (request.system.isNotBlank()) addJsonObject {
            put("role", "system")
            put("content", request.system)
        }
        for (message in request.messages) {
            when (message) {
                is ChatMessage.User -> addJsonObject {
                    put("role", "user")
                    put("content", message.parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text })
                    val images = message.parts.filterIsInstance<Part.Image>()
                    if (images.isNotEmpty()) putJsonArray("images") { images.forEach { add(JsonPrimitive(Wire.base64(it.bytes))) } }
                }
                is ChatMessage.Assistant -> addJsonObject {
                    put("role", "assistant")
                    put("content", Wire.assistantText(message, callsAsText = !tools))
                    if (tools && message.toolCalls.isNotEmpty()) putJsonArray("tool_calls") {
                        for (call in message.toolCalls) addJsonObject {
                            putJsonObject("function") {
                                put("name", call.name)
                                put("arguments", call.arguments)
                            }
                        }
                    }
                }
                is ChatMessage.ToolResult -> addJsonObject {
                    if (tools) {
                        put("role", "tool")
                        put("tool_name", message.name)
                        put("content", message.text)
                    } else {
                        put("role", "user")
                        put("content", Wire.resultText(message))
                    }
                }
            }
        }
    }

    private fun read(body: JsonObject, request: ChatRequest, target: ModelTarget): ChatResponse {
        body.str("error")?.let { throw ProviderException(Wire.streamError(null, null, target)) }
        if (body.bool("done") != true) throw ProviderException(ProviderError.Malformed)
        val message = body.obj("message") ?: throw ProviderException(ProviderError.Malformed)
        val reply = ReplyBuilder(request)
        reply.text(message.str("content").orEmpty())
        val first = Wire.nextSyntheticNumber(request, SYNTHETIC_ID)
        message.arr("tool_calls").objects().forEachIndexed { index, call ->
            val function = call.obj("function")
            reply.call(call.str("id") ?: "$SYNTHETIC_ID${first + index}", function?.str("name").orEmpty(), function?.get("arguments"))
        }
        val stop = when (body.str("done_reason")) {
            null, "stop" -> StopReason.END
            "length" -> StopReason.MAX_TOKENS
            else -> StopReason.OTHER
        }
        val prompt = body.long("prompt_eval_count")
        val output = body.long("eval_count")
        val usage = if (prompt == null && output == null) {
            Wire.estimate(request, reply.replyChars, message.str("thinking")?.length ?: 0, target.model.reasoning)
        } else {
            val cached = body.long("prompt_eval_cached_count") ?: 0
            Usage(input = Wire.minus(prompt ?: 0, cached), output = output ?: 0, cacheRead = cached)
        }
        return reply.response(stop, usage, body.str("model") ?: target.sentModelId)
    }

    companion object {
        /** The prefix of the IDs that Kestrel gives calls; they are numbered after the highest one in the request. */
        const val SYNTHETIC_ID = "ollama-call-"

        /**
         * The largest `num_ctx` that Kestrel asks for. A model's full window (262,144 tokens for some) can use up the memory
         * of the owner's computer, and an agent step needs far less.
         */
        const val MAX_CONTEXT = 32_768

        /** The server's origin: Ollama's paths start there, whatever path the owner typed (design D11). */
        fun origin(baseUrl: HttpUrl): HttpUrl = baseUrl.newBuilder().encodedPath("/").query(null).fragment(null).build()
    }
}
