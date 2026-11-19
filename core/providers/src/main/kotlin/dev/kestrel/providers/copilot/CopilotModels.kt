package dev.kestrel.providers.copilot

import dev.kestrel.providers.catalog.InputKind
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.discovery.DiscoveredModel
import dev.kestrel.providers.discovery.ModelDiscovery
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.http.RequestAuth
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl

/**
 * Copilot's model list (design D12; research r27): `GET <base>/models` with the runtime headers and the bearer. A model is
 * shown when `model_picker_enabled` is true, its `policy.state` is neither `disabled` nor `unconfigured`, its `object` is
 * `model` and its `capabilities.type` `chat` (or absent), it streams (Kestrel streams every Copilot request) and its ID
 * does not start with `accounts/`. Its tool support, image input, reasoning efforts and limits come from its
 * capabilities, and its style from its `supported_endpoints`.
 */
class CopilotModels(
    private val http: ProviderHttp,
    private val base: HttpUrl,
    private val auth: RequestAuth,
    private val runtimeHeaders: Map<String, String>,
) : ModelDiscovery {
    private val json = Json { isLenient = true }

    override suspend fun list(): List<DiscoveredModel> {
        val url = base.newBuilder().addPathSegment("models").build()
        val reply = http.send(HttpRequest("GET", url, runtimeHeaders + ("Accept" to "application/json")), auth)
        val body = try {
            json.parseToJsonElement(reply.text()) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        }
        val data = body?.get("data") as? JsonArray ?: throw ProviderException(ProviderError.Malformed)
        return data.mapNotNull { (it as? JsonObject)?.let(::model) }
    }

    companion object {
        private val GEMINI = Regex("(?:^|[-_.])gemini(?:$|[-_.])")
        private val PATHS = mapOf(
            RequestStyle.ANTHROPIC to "/messages",
            RequestStyle.CHAT_COMPLETIONS to "/chat/completions",
            RequestStyle.RESPONSES to "/responses",
        )

        /** One row of the list, or null when D12's filters hide it. */
        fun model(entry: JsonObject): DiscoveredModel? {
            val id = entry.text("id")?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("accounts/") } ?: return null
            if (entry.text("object")?.let { it != "model" } == true) return null
            val capabilities = entry["capabilities"] as? JsonObject
            if (capabilities?.text("type")?.let { it != "chat" } == true) return null
            if (entry.flag("model_picker_enabled") != true) return null
            if ((entry["policy"] as? JsonObject)?.text("state") in setOf("disabled", "unconfigured")) return null
            val supports = capabilities?.get("supports") as? JsonObject
            if (supports?.flag("streaming") == false) return null
            val limits = capabilities?.get("limits") as? JsonObject
            val efforts = (supports?.get("reasoning_effort") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            val endpoints = (entry["supported_endpoints"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
            return DiscoveredModel(
                id = id,
                name = entry.text("name")?.takeIf { it.isNotBlank() } ?: id,
                input = if (supports?.flag("vision") == true) setOf(InputKind.TEXT, InputKind.IMAGE) else setOf(InputKind.TEXT),
                reasoning = !efforts.isNullOrEmpty(),
                reasoningEfforts = efforts?.takeIf { it.isNotEmpty() },
                contextWindow = limits?.number("max_context_window_tokens"),
                maxTokens = limits?.number("max_output_tokens"),
                tools = supports?.flag("tool_calls") == true,
                streaming = supports?.flag("streaming"),
                style = styleFor(id, entry.text("vendor"), endpoints),
            )
        }

        /**
         * The style of a model (research r27): the preferred style is Anthropic Messages for vendor `anthropic` or an ID
         * with `claude`, Chat Completions for a Gemini ID, otherwise Responses; the first of the preferred style, Responses,
         * Chat Completions and Anthropic Messages whose path the model lists wins, and without any the preferred style.
         */
        fun styleFor(id: String, vendor: String?, endpoints: List<String>): RequestStyle {
            val preferred = when {
                vendor.equals("anthropic", ignoreCase = true) || "claude" in id.lowercase() -> RequestStyle.ANTHROPIC
                GEMINI.containsMatchIn(id.lowercase()) -> RequestStyle.CHAT_COMPLETIONS
                else -> RequestStyle.RESPONSES
            }
            val listed = endpoints.map { it.trim().removePrefix("/v1").trimEnd('/') }.toSet()
            return listOf(preferred, RequestStyle.RESPONSES, RequestStyle.CHAT_COMPLETIONS, RequestStyle.ANTHROPIC)
                .firstOrNull { PATHS.getValue(it) in listed } ?: preferred
        }

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

        private fun JsonObject.number(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

        private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
    }
}
