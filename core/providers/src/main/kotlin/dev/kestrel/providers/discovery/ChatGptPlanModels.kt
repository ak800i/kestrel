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
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * The ChatGPT plan's model list (design D13; research r46; OpenAI's "Models and inference"): `GET
 * https://api.openai.com/v1/models` with the plan's bearer, whose rows have `slug`, `display_name` and `visibility`; only
 * the rows with `visibility` `list` are shown. Their requests use Responses with the plan's preview limits, which accept
 * function tools in a namespace, so each row has tool support. An account without Plus or Pro gets
 * `subscription_sharing_user_not_eligible` (403), which reaches the caller as QuotaOrPolicy with that code.
 */
class ChatGptPlanModels(private val http: ProviderHttp, private val auth: RequestAuth) : ModelDiscovery {
    private val json = Json { isLenient = true }

    override suspend fun list(): List<DiscoveredModel> {
        val reply = http.send(HttpRequest("GET", URL.toHttpUrl(), mapOf("Accept" to "application/json")), auth)
        val body = try {
            json.parseToJsonElement(reply.text()) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        }
        val models = body?.get("models") as? JsonArray ?: throw ProviderException(ProviderError.Malformed)
        return models.mapNotNull { row ->
            val model = row as? JsonObject ?: return@mapNotNull null
            if (model.text("visibility") != "list") return@mapNotNull null
            val slug = model.text("slug")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            DiscoveredModel(slug, model.text("display_name")?.takeIf { it.isNotBlank() } ?: slug, tools = true, style = RequestStyle.RESPONSES)
        }
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private companion object {
        const val URL = "https://api.openai.com/v1/models"
    }
}
