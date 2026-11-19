package dev.kestrel.providers.styles

import dev.kestrel.providers.catalog.CompatFlags
import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.ChatResponse
import dev.kestrel.providers.chat.InvalidKind
import dev.kestrel.providers.chat.InvalidToolCall
import dev.kestrel.providers.chat.ModelTarget
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.chat.ReasoningState
import dev.kestrel.providers.chat.StopReason
import dev.kestrel.providers.chat.Usage
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import java.security.MessageDigest
import java.util.Base64

/**
 * What every adapter shares (design D3 to D6): building and reading JSON, the replay of tool history as text, the
 * reasoning level of design D5, the estimate of design D6, and the mapping of an error inside a stream (design D4).
 */
internal object Wire {
    val json = Json { ignoreUnknownKeys = true }

    private val CODE = Regex("^[A-Za-z0-9_.:-]{1,64}$")
    const val ANTHROPIC_ID_CHARS = "a-zA-Z0-9_-"
    const val CONVERSE_ID_CHARS = "a-zA-Z0-9_.:-"
    private val LEVELS = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")
    private val EFFORTS = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")

    // OpenClaw's effort lists (`packages/ai/src/providers/openai-reasoning-effort.ts` at the pinned commit).
    private val GENERIC_EFFORTS = listOf("low", "medium", "high")
    private val GPT_5_EFFORTS = listOf("minimal", "low", "medium", "high")
    private val GPT_51_EFFORTS = listOf("none", "low", "medium", "high")
    private val GPT_52_EFFORTS = listOf("none", "low", "medium", "high", "xhigh")
    private val GPT_56_EFFORTS = listOf("none", "low", "medium", "high", "xhigh", "max")
    private val GPT_6_MANDATORY_EFFORTS = listOf("low", "medium", "high", "xhigh", "max")
    private val GPT_CODEX_EFFORTS = listOf("low", "medium", "high", "xhigh")
    private val GPT_PRO_EFFORTS = listOf("medium", "high", "xhigh")
    private val SERVER_CODES = setOf("server_error", "api_error", "overloaded_error", "internal_error", "internal", "unavailable", "service_unavailable")
    private val POLICY_CODES = setOf("permission_error", "billing_error", "permission_denied", "insufficient_quota")

    /**
     * A JSON request to [url]. The adapter adds only `accept` and its [extra] headers; the target's headers (design
     * "Shared names") come last, and the credential's header comes from RequestAuth in ProviderHttp.
     */
    fun post(url: HttpUrl, body: JsonObject, target: ModelTarget, request: ChatRequest, stream: Boolean, extra: Map<String, String> = emptyMap(), logPath: String? = null): HttpRequest =
        HttpRequest(
            method = "POST",
            url = url,
            headers = mapOf("accept" to if (stream) "text/event-stream" else "application/json") + extra + target.headers.headers(request),
            body = body.toString().encodeToByteArray(),
            contentType = "application/json",
            meta = if (logPath == null) request.meta else request.meta.copy(logPath = logPath),
        )

    /** Parses a JSON object, or fails with Malformed: a reply that Kestrel cannot read (design D17). */
    fun parse(text: String): JsonObject =
        try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } ?: throw ProviderException(ProviderError.Malformed)

    fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /** Keeps a provider's machine code only when it is one (design D17). */
    fun code(value: String?): String? = value?.takeIf(CODE::matches)

    /**
     * Maps an error that a stream reports with status 200 like an HTTP error (design D4): a rate limit, a server error or
     * an overloaded server, a refused credential, a refused permission or payment, and otherwise a bad request.
     */
    fun streamError(status: Int?, rawCode: String?, target: ModelTarget): ProviderError {
        val code = code(rawCode)
        val name = code?.lowercase().orEmpty()
        return when {
            status == 429 || "rate_limit" in name || name == "resource_exhausted" || name == "too_many_requests" ->
                ProviderError.RateLimited(null, code)
            (status != null && status >= 500) || name in SERVER_CODES -> ProviderError.ServerError(status?.takeIf { it >= 500 } ?: 500)
            status == 401 || name == "authentication_error" || name == "unauthenticated" -> target.auth.rejected
            status == 402 || status == 403 || name in POLICY_CODES -> ProviderError.QuotaOrPolicy(status ?: 403, code)
            else -> ProviderError.BadRequest(status ?: 400, code)
        }
    }

    /** How a tool call goes back as text when the request sends no tools (design D5, research r39). */
    fun callText(call: AssistantPart.ToolCall): String = "[tool call id=${call.id} name=${call.name}] ${call.arguments}"

    fun resultText(result: ChatMessage.ToolResult): String = "[tool result id=${result.callId} name=${result.name}] ${result.text}"

    /** The text of an assistant message, with its tool calls as text when [callsAsText] is true. */
    fun assistantText(message: ChatMessage.Assistant, callsAsText: Boolean): String {
        val texts = message.parts.filterIsInstance<AssistantPart.Text>().map { it.text }
        val calls = if (callsAsText) message.toolCalls.map(::callText) else emptyList()
        return (texts + calls).filter { it.isNotEmpty() }.joinToString("\n")
    }

    /**
     * The number after the highest made-up call ID with [prefix] in the request's history, or 0: a style whose provider
     * gives no call IDs numbers its calls from here, so that IDs do not repeat across a task's steps.
     */
    fun nextSyntheticNumber(request: ChatRequest, prefix: String): Int =
        request.messages.filterIsInstance<ChatMessage.Assistant>().flatMap { it.toolCalls }
            .mapNotNull { call -> call.id.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.toIntOrNull() }
            .maxOrNull()?.plus(1) ?: 0

    /**
     * The reasoning level that a request gets (design D5), as OpenClaw names it (`minimal` to `max`), or null when the
     * request asks for none, the model does not reason, or the model's only level is `off`. A level that the model's
     * `thinkingLevels` lacks, or that its `thinkingLevelMap` maps to null, moves to the nearest allowed one, downwards
     * first, as OpenClaw moves an effort.
     */
    fun level(request: ChatRequest, model: ModelEntry, compat: CompatFlags): String? {
        val asked = request.reasoning ?: return null
        if (!model.reasoning) return null
        val allowed = (compat.thinkingLevels ?: LEVELS).filter { it != "off" && !(model.thinkingLevelMap.containsKey(it) && model.thinkingLevelMap[it] == null) }
        if (allowed.isEmpty()) return null
        return nearest(asked.name.lowercase(), allowed, LEVELS)
    }

    /**
     * The provider's effort word for [level] (design D5), as OpenClaw's `resolveOpenAIReasoningEffortForModel` chooses
     * it: the model's `thinkingLevelMap`, then the nearest of the efforts that the model accepts, then its
     * `reasoningEffortMap`. The model accepts its `supportedReasoningEfforts`; a model without that list accepts OpenClaw's
     * list for its GPT-5 or GPT-6 family, or else OpenClaw's generic `low`, `medium` and `high` and the efforts that its
     * `thinkingLevelMap` names, so that `minimal` becomes `low` on DeepSeek and the other rows without a list.
     * [chatCompletions] says that the request goes to Chat Completions, where OpenClaw gives GPT-5.6 and GPT-6 no `max`.
     * Null when the effort would be `none`, or when the model's compat says `supportsReasoningEffort: false`, for which
     * OpenClaw sends no effort at all.
     */
    fun effort(level: String, model: ModelEntry, compat: CompatFlags, chatCompletions: Boolean = false): String? {
        if (compat.supportsReasoningEffort == false) return null
        val mapped = model.thinkingLevelMap[level] ?: level
        if (mapped == "none") return null
        val declared = compat.supportedReasoningEfforts ?: openAiEfforts(model.id, chatCompletions)
        val supported = (declared ?: (GENERIC_EFFORTS + model.thinkingLevelMap.values.filterNotNull())).filter { it != "none" }.distinct()
        val chosen = when {
            mapped in supported -> mapped
            supported.isEmpty() -> return null
            else -> nearest(mapped, supported, EFFORTS)
        }
        return compat.reasoningEffortMap?.get(chosen) ?: chosen
    }

    /** OpenClaw's `resolveOpenAIModelReasoningEfforts` for a model that declares no list: its known GPT families, or null. */
    private fun openAiEfforts(modelId: String, chatCompletions: Boolean): List<String>? {
        val id = modelId.trim().lowercase().replace(Regex("-\\d{4}-\\d{2}-\\d{2}$"), "")
        val max = !chatCompletions
        return when {
            id == "gpt-6-astra" || id == "gpt-6.1-sol" -> if (max) GPT_6_MANDATORY_EFFORTS else GPT_CODEX_EFFORTS
            id == "gpt-6-sol" || id == "gpt-6-luna" || Regex("^gpt-5\\.6(?:-|$)").containsMatchIn(id) -> if (max) GPT_56_EFFORTS else GPT_52_EFFORTS
            id == "gpt-5.1-codex-mini" -> listOf("medium")
            id == "gpt-5.1-codex-max" -> listOf("none", "medium", "high", "xhigh")
            Regex("^gpt-5(?:\\.\\d+)?-codex(?:-|$)").containsMatchIn(id) -> GPT_CODEX_EFFORTS
            id == "gpt-5-pro" -> listOf("high")
            Regex("^gpt-5\\.[2-9](?:\\.\\d+)?-pro(?:-|$)").containsMatchIn(id) -> GPT_PRO_EFFORTS
            Regex("^gpt-5\\.[2-9](?:\\.\\d+)?(?:-|$)").containsMatchIn(id) -> GPT_52_EFFORTS
            Regex("^gpt-5\\.1(?:-|$)").containsMatchIn(id) -> GPT_51_EFFORTS
            Regex("^gpt-5(?:-|$)").containsMatchIn(id) -> GPT_5_EFFORTS
            else -> null
        }
    }

    /** As OpenClaw moves an effort: the highest allowed one at or below [wanted], or else the lowest allowed one. */
    private fun nearest(wanted: String, allowed: List<String>, order: List<String>): String {
        if (wanted in allowed) return wanted
        val index = order.indexOf(wanted)
        if (index < 0) return allowed.first()
        val ranked = order.filter { it in allowed }
        return ranked.lastOrNull { order.indexOf(it) <= index } ?: ranked.firstOrNull() ?: allowed.first()
    }

    /**
     * Counts tokens when a reply has no usage (design D6): a quarter of the request's characters plus 1,000 per image as
     * input, and a quarter of the reply's and of the streamed reasoning's characters as output, doubled for a reasoning
     * model whose stream showed no reasoning, because hidden reasoning is billed as output.
     */
    fun estimate(request: ChatRequest, replyChars: Int, reasoningChars: Int, reasoningModel: Boolean): Usage {
        var chars = request.system.length.toLong()
        var images = 0L
        for (tool in request.tools) chars += tool.name.length + tool.description.length + tool.parameters.toString().length
        for (message in request.messages) {
            when (message) {
                is ChatMessage.User -> message.parts.forEach { part ->
                    when (part) {
                        is Part.Text -> chars += part.text.length
                        is Part.Image -> images++
                    }
                }
                is ChatMessage.Assistant -> message.parts.forEach { part ->
                    when (part) {
                        is AssistantPart.Text -> chars += part.text.length
                        is AssistantPart.ToolCall -> chars += part.name.length + part.arguments.toString().length
                        is AssistantPart.Reasoning -> Unit
                    }
                }
                is ChatMessage.ToolResult -> chars += message.text.length
            }
        }
        val reasoning = reasoningChars / 4L
        var output = replyChars / 4L + reasoning
        if (reasoningModel && reasoningChars == 0) output *= 2
        return Usage(input = chars / 4 + images * 1_000, output = output, reasoning = reasoning, estimated = true)
    }

    /** Subtracts without going below zero, as OpenClaw does (design D6). */
    fun minus(total: Long, vararg parts: Long): Long = (total - parts.sum()).coerceAtLeast(0)

    /**
     * A tool-use ID with only the characters of [chars] (a regex character class without its brackets, which must hold
     * `_`) and 1 to 64 characters: Anthropic's `^[a-zA-Z0-9_-]+$` ([ANTHROPIC_ID_CHARS]), which Copilot's Claude models
     * take too (design D12), as OpenClaw's `normalizeAnthropicToolCallId` makes it, or Converse's `[a-zA-Z0-9_.:-]+`
     * ([CONVERSE_ID_CHARS], AWS's `ToolUseBlock` reference). An ID that already fits stays as it is; any other becomes its
     * allowed characters (each other character as `_`), cut so that `_` and the first 8 hex digits of the SHA-256 of the
     * original ID fit in 64 characters. The result depends only on the ID, so a call and its result always pair up, and a
     * history whose old turns are trimmed keeps each surviving ID's rewrite; the hash keeps two IDs with the same allowed
     * characters apart.
     */
    fun toolUseId(id: String, chars: String = ANTHROPIC_ID_CHARS): String {
        if (Regex("^[$chars]{1,64}$").matches(id)) return id
        val hash = MessageDigest.getInstance("SHA-256").digest(id.encodeToByteArray())
            .take(4).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return id.replace(Regex("[^$chars]"), "_").take(64 - 1 - hash.length) + "_" + hash
    }

    fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

    fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

    fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()

    fun JsonElement?.objects(): List<JsonObject> = (this as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
}

/**
 * Counts what one reply gathers from a stream: its text, its reasoning and its tool calls' arguments together. Above
 * [MAX_CHARS] (8 Mi characters, as ProviderHttp's limit of 8 MiB for one body or event) the reply is Malformed, so that
 * a stream of many small events cannot use up the app's memory.
 */
internal class GatherLimit {
    private var total = 0L

    fun add(chars: Int) {
        total += chars
        if (total > MAX_CHARS) throw ProviderException(ProviderError.Malformed)
    }

    companion object {
        const val MAX_CHARS = 8L * 1024 * 1024
    }
}

/**
 * Gathers one assistant reply in the order that the provider gave its parts, checks each tool call against the request
 * (design D3), and builds the [ChatResponse]. A call to a tool that the request does not have, or whose arguments are no
 * JSON object, is left out of the message and reported as [InvalidToolCall] with its raw arguments as text. A call whose
 * ID is blank or repeats an earlier call's ID of the same reply gets a fresh `call_<n>`, numbered after the `call_<n>`
 * IDs of the request's history, so that a task's history never repeats one; an ID that the provider sent is kept.
 */
internal class ReplyBuilder(request: ChatRequest) {
    private val parts = mutableListOf<AssistantPart>()
    private var invalid: InvalidToolCall? = null
    private val tools = request.tools.map { it.name }.toSet()
    private val firstFresh = Wire.nextSyntheticNumber(request, "call_")
    var replyChars = 0
        private set

    val hasCalls: Boolean get() = parts.any { it is AssistantPart.ToolCall }

    fun text(text: String) {
        if (text.isEmpty()) return
        replyChars += text.length
        val last = parts.lastOrNull()
        if (last is AssistantPart.Text) parts[parts.size - 1] = AssistantPart.Text(last.text + text) else parts += AssistantPart.Text(text)
    }

    fun reasoning(state: ReasoningState) {
        parts += AssistantPart.Reasoning(state)
    }

    /**
     * A call whose arguments came as text, as in Chat Completions and Responses; a blank text means no arguments. Returns
     * the call's final ID, or null when the call is left out.
     */
    fun call(id: String, name: String, arguments: String): String? {
        replyChars += name.length + arguments.length
        val parsed = if (arguments.isBlank()) JsonObject(emptyMap()) else try {
            Wire.json.parseToJsonElement(arguments)
        } catch (e: SerializationException) {
            null
        }
        return add(id, name, parsed, arguments)
    }

    /** A call whose arguments came as JSON, as in Anthropic Messages, Gemini, Bedrock and Ollama; returns as the other. */
    fun call(id: String, name: String, arguments: JsonElement?): String? {
        val text = arguments?.toString().orEmpty()
        replyChars += name.length + text.length
        return add(id, name, arguments ?: JsonObject(emptyMap()), text)
    }

    /** A call that the provider itself reports as unusable, such as Gemini's MALFORMED_FUNCTION_CALL. */
    fun invalid(kind: InvalidKind, raw: String) {
        invalid = invalid ?: InvalidToolCall(kind, raw)
    }

    private fun add(id: String, name: String, arguments: JsonElement?, raw: String): String? {
        when {
            name !in tools -> invalid = invalid ?: InvalidToolCall(InvalidKind.UNKNOWN_TOOL, raw)
            arguments !is JsonObject -> invalid = invalid ?: InvalidToolCall(InvalidKind.ARGUMENTS_NOT_OBJECT, raw)
            else -> return unique(id).also { parts += AssistantPart.ToolCall(it, name, arguments) }
        }
        return null
    }

    /** A blank or repeated ID gets a fresh one, because the next request needs one result per ID (design D3). */
    private fun unique(id: String): String {
        val taken = parts.filterIsInstance<AssistantPart.ToolCall>().map { it.id }.toSet()
        if (id.isNotBlank() && id !in taken) return id
        var n = maxOf(taken.size + 1, firstFresh)
        while ("call_$n" in taken) n++
        return "call_$n"
    }

    /** A reply that holds a valid tool call stops with TOOL_CALL, whatever the provider's stop reason said. */
    fun response(stop: StopReason, usage: Usage, modelId: String): ChatResponse =
        ChatResponse(
            message = ChatMessage.Assistant(parts.toList()),
            stop = if (hasCalls && stop == StopReason.END) StopReason.TOOL_CALL else stop,
            usage = usage,
            modelId = modelId,
            invalidToolCall = invalid,
        )
}
