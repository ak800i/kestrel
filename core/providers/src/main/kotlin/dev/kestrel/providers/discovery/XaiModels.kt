package dev.kestrel.providers.discovery

import dev.kestrel.providers.chat.RequestStyle
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
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * The model list of xAI's sign-in (design D13; research r31): `GET https://cli-chat-proxy.grok.com/v1/models` with the
 * sign-in's bearer and the choice's identity [headers]. Every row goes through Responses, whatever its `api_backend`
 * (`responses`, `chat` or `language`), as OpenClaw's `buildXaiOauthModelFromLiveRow` and research r31 do: the Grok CLI's
 * proxy is configured with `api: "openai-responses"`, and `api_backend` only tells which rows exist; a row with another
 * backend, such as an image model, is dropped. `reasoning_efforts` gives its efforts, as strings or as objects with a
 * `value`. The rows are read from `data`, or from `models` when there is no `data`.
 */
class XaiModels(private val http: ProviderHttp, private val auth: RequestAuth, private val headers: Map<String, String> = emptyMap()) : ModelDiscovery {
    private val json = Json { isLenient = true }

    override suspend fun list(): List<DiscoveredModel> {
        val reply = http.send(HttpRequest("GET", URL.toHttpUrl(), headers + ("Accept" to "application/json")), auth)
        val body = try {
            json.parseToJsonElement(reply.text()) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        }
        val rows = (body?.get("data") ?: body?.get("models")) as? JsonArray ?: throw ProviderException(ProviderError.Malformed)
        return rows.mapNotNull { row ->
            val model = row as? JsonObject ?: return@mapNotNull null
            val id = (model.text("id") ?: model.text("model"))?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (model.text("api_backend").let { it != null && it !in BACKENDS }) return@mapNotNull null
            val efforts = (model["reasoning_efforts"] as? JsonArray)?.mapNotNull(::effort)
            DiscoveredModel(
                id = id,
                name = id,
                reasoning = model.flag("supports_reasoning_effort") == true || !efforts.isNullOrEmpty(),
                reasoningEfforts = efforts?.takeIf { it.isNotEmpty() },
                contextWindow = model.number("context_window"),
                maxTokens = model.number("max_completion_tokens"),
                style = RequestStyle.RESPONSES,
            )
        }
    }

    private fun effort(entry: JsonElement): String? =
        ((entry as? JsonObject)?.get("value") ?: entry).let { it as? JsonPrimitive }?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    private fun JsonObject.number(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    private companion object {
        const val URL = "https://cli-chat-proxy.grok.com/v1/models"
        val BACKENDS = setOf("responses", "chat", "language")
    }
}
