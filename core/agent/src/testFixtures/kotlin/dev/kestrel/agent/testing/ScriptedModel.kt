package dev.kestrel.agent.testing

import dev.kestrel.agent.port.ModelInfo
import dev.kestrel.agent.port.ModelPort
import dev.kestrel.agent.port.RoleModel
import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.ChatResponse
import dev.kestrel.providers.chat.InvalidKind
import dev.kestrel.providers.chat.InvalidToolCall
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.chat.StopReason
import dev.kestrel.providers.chat.Usage
import dev.kestrel.providers.client.PricedResponse
import dev.kestrel.providers.cost.Cost
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.profiles.ModelRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A model that follows a script (design D27 and D28): the JVM tests and `:app`'s debug hook `agent-script` use it, so no
 * test and no dry run calls a provider. Each reply of [replies] is one JSON object:
 * - `{"tool": "tap", "target": {"label": "Send", "class": "Button"}, "arguments": {"reason": "…", "category": "sending"}}`:
 *   one call; a `target` names an element of the newest observation by its label (and optionally a part of its class),
 *   which the script resolves to the element's number, so that scripts survive changes of numbering; it may also be a
 *   number, and a `target` inside the arguments (`finish`'s evidence) is resolved the same way;
 * - `{"calls": [ {…}, {…} ]}`: several calls in one reply;
 * - `{"text": "…"}`: text without a call;
 * - `{"invalid": "UNKNOWN_TOOL"}`: a call that the adapter could not use (an `InvalidKind`);
 * - `{"stop": "MAX_TOKENS", …}` or `"FILTERED"` with or without calls: a cut reply;
 * - `{"error": "Timeout"}`, `{"error": "BadRequest", "toolChoice": true}`, `{"error": "ServerError", "status": 503}`,
 *   `{"error": "RateLimited"}`, `{"error": "Unreachable"}`, `{"error": "Malformed"}`, `{"error": "QuotaOrPolicy"}`:
 *   the call throws that provider error.
 * Any reply may carry `"credits": 0.5` (the default is 0.1). When the script is used up, the model fails the task with
 * "The script has no more replies." toString prints only counts.
 */
class ScriptedModel(
    replies: List<JsonObject>,
    override val model: ModelInfo = INFO,
) : ModelPort {
    private val script = ArrayDeque(replies)
    private val recorded = mutableListOf<Pair<ModelRole, ChatRequest>>()

    val requests: List<ChatRequest> get() = recorded.map { it.second }
    val roles: List<ModelRole> get() = recorded.map { it.first }
    val left: Int get() = script.size

    override suspend fun call(role: ModelRole, request: ChatRequest, step: Int): PricedResponse {
        recorded += role to request
        val reply = script.removeFirstOrNull() ?: buildJsonObject {
            put("tool", "fail")
            put("arguments", buildJsonObject { put("reason", "The script has no more replies.") })
        }
        reply["error"]?.jsonPrimitive?.contentOrNull?.let { throw ProviderException(error(it, reply)) }
        val credits = reply["credits"]?.jsonPrimitive?.doubleOrNull ?: DEFAULT_CREDITS
        val parts = mutableListOf<AssistantPart>()
        reply["text"]?.jsonPrimitive?.contentOrNull?.let { parts += AssistantPart.Text(it) }
        val calls = (reply["calls"] as? JsonArray)?.map { it.jsonObject } ?: listOfNotNull(reply.takeIf { "tool" in it })
        calls.forEachIndexed { index, call -> parts += call(call, request, "call-${recorded.size}-${index + 1}") }
        val invalid = reply["invalid"]?.jsonPrimitive?.contentOrNull?.let { InvalidToolCall(InvalidKind.valueOf(it), "scripted") }
        val stop = reply["stop"]?.jsonPrimitive?.contentOrNull?.let { StopReason.valueOf(it) }
            ?: if (parts.any { it is AssistantPart.ToolCall }) StopReason.TOOL_CALL else StopReason.END
        val id = if (role == ModelRole.LIGHTWEIGHT) model.lightweight.id else model.versatile.id
        val response = ChatResponse(ChatMessage.Assistant(parts), stop, Usage(input = 1_000, output = 50, cacheRead = 500), id, invalid)
        return PricedResponse(response, Cost(credits / 100, credits, estimated = false, priceKnown = true, planUsage = false), RequestStyle.CHAT_COMPLETIONS)
    }

    private fun call(call: JsonObject, request: ChatRequest, id: String): AssistantPart.ToolCall {
        val arguments = (call["arguments"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        call["target"]?.let { arguments["target"] = it }
        return AssistantPart.ToolCall(id, call["tool"]!!.jsonPrimitive.content, resolveAll(JsonObject(arguments), request) as JsonObject)
    }

    // Every "target" that names an element by its label, also inside finish's evidence, becomes the element's number.
    private fun resolveAll(element: JsonElement, request: ChatRequest): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (key, value) ->
            if (key == "target" && value is JsonObject) JsonPrimitive(resolve(value, request)) else resolveAll(value, request)
        })
        is JsonArray -> JsonArray(element.map { resolveAll(it, request) })
        else -> element
    }

    /**
     * The number of the newest observation's element whose label is the target's `label`, or holds its `contains` without
     * letter case (plan 6B: a real app's row joins several texts into one label), and whose class holds `class`.
     */
    private fun resolve(target: JsonObject, request: ChatRequest): Int {
        val label = target["label"]?.jsonPrimitive?.contentOrNull
        val contains = target["contains"]?.jsonPrimitive?.contentOrNull
        require((label == null) != (contains == null)) { "A script's target needs a label or a part of one" }
        val className = target["class"]?.jsonPrimitive?.contentOrNull
        for (text in texts(request).asReversed()) {
            val lines = LINE.findAll(text).toList()
            if (lines.isEmpty()) continue
            val line = lines.firstOrNull { match ->
                val shown = unescape(match.groupValues[3])
                (if (label != null) shown == label else shown.contains(contains!!, ignoreCase = true)) &&
                    (className == null || match.groupValues[2].contains(className, ignoreCase = true))
            } ?: throw IllegalStateException("The newest observation has no element for the script's target")
            return line.groupValues[1].toInt()
        }
        throw IllegalStateException("The request has no observation")
    }

    private fun texts(request: ChatRequest): List<String> = request.messages.mapNotNull { message ->
        when (message) {
            is ChatMessage.ToolResult -> message.text
            is ChatMessage.User -> message.parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text }
            is ChatMessage.Assistant -> null
        }
    }

    private fun error(name: String, reply: JsonObject): ProviderError {
        val status = reply["status"]?.jsonPrimitive?.intOrNull
        return when (name) {
            "Timeout" -> ProviderError.Timeout
            "Unreachable" -> ProviderError.Unreachable
            "Malformed" -> ProviderError.Malformed
            "Unauthorized" -> ProviderError.Unauthorized
            "NotAllowed" -> ProviderError.NotAllowed
            "RateLimited" -> ProviderError.RateLimited(null)
            "ServerError" -> ProviderError.ServerError(status ?: 500)
            "QuotaOrPolicy" -> ProviderError.QuotaOrPolicy(status ?: 402)
            "BadRequest" -> ProviderError.BadRequest(status ?: 400, null, reply["toolChoice"]?.jsonPrimitive?.booleanOrNull ?: false)
            else -> throw IllegalArgumentException("Unknown error in the script")
        }
    }

    override fun toString(): String = "ScriptedModel(left=${script.size}, calls=${recorded.size})"

    companion object {
        const val DEFAULT_CREDITS = 0.1
        private val LINE = Regex("""(?m)^\[(\d+)]\*?\s+(\S+)\s+(?:hint |id |password |OCR )?"((?:[^"\\]|\\.)*)"""")

        val INFO = ModelInfo(
            providerName = "Scripted",
            providerId = "scripted",
            countsCredits = true,
            localOllama = false,
            lightweight = RoleModel("Scripted light", "scripted-light", RequestStyle.CHAT_COMPLETIONS, 128_000, takesImages = false, signedThinking = false),
            versatile = RoleModel("Scripted versatile", "scripted-versatile", RequestStyle.CHAT_COMPLETIONS, 128_000, takesImages = true, signedThinking = false),
        )

        /** A script in its JSON form: `{"replies": [ … ]}`. */
        fun fromJson(text: String, model: ModelInfo = INFO): ScriptedModel =
            ScriptedModel(Json.parseToJsonElement(text).jsonObject["replies"]!!.jsonArray.map { it.jsonObject }, model)

        /** One reply with one call, for the Kotlin tests. */
        fun call(tool: String, label: String? = null, className: String? = null, credits: Double? = null, vararg arguments: Pair<String, Any?>): JsonObject =
            buildJsonObject {
                put("tool", tool)
                if (label != null) put("target", buildJsonObject { put("label", label); if (className != null) put("class", className) })
                put("arguments", JsonObject(arguments.associate { (key, value) -> key to json(value) }))
                if (credits != null) put("credits", credits)
            }

        fun json(value: Any?): JsonElement = when (value) {
            null -> kotlinx.serialization.json.JsonNull
            is JsonElement -> value
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is List<*> -> JsonArray(value.map(::json))
            is Map<*, *> -> JsonObject(value.entries.associate { (key, item) -> key.toString() to json(item) })
            else -> throw IllegalArgumentException("Not a JSON value")
        }

        private fun unescape(text: String): String = text.replace("\\\"", "\"").replace("\\\\", "\\")
    }
}
