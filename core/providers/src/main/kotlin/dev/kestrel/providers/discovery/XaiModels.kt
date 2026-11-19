package dev.kestrel.providers.discovery

import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.http.RequestAuth
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * The model list of xAI's sign-in (design D13; research r31): `GET https://cli-chat-proxy.grok.com/v1/models` with the
 * sign-in's bearer and the choice's identity [headers]. Each row's `api_backend` gives its style: `responses` Responses,
 * `chat` Chat Completions, and `language` rows are dropped; `reasoning_efforts` gives its efforts. The rows are read from
 * `data`, or from `models` when there is no `data`.
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
            val style = when (model.text("api_backend")) {
                null, "responses" -> RequestStyle.RESPONSES
                "chat" -> RequestStyle.CHAT_COMPLETIONS
                else -> return@mapNotNull null
            }
            val efforts = (model["reasoning_efforts"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            DiscoveredModel(
                id = id,
                name = id,
                reasoning = model.flag("supports_reasoning_effort") == true || !efforts.isNullOrEmpty(),
                reasoningEfforts = efforts?.takeIf { it.isNotEmpty() },
                contextWindow = model.number("context_window"),
                maxTokens = model.number("max_completion_tokens"),
                style = style,
            )
        }
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    private fun JsonObject.number(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    private companion object {
        const val URL = "https://cli-chat-proxy.grok.com/v1/models"
    }
}
