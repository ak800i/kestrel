package dev.kestrel.providers.discovery

import dev.kestrel.providers.catalog.Discovery
import dev.kestrel.providers.catalog.InputKind
import dev.kestrel.providers.catalog.LocalServerKind
import dev.kestrel.providers.catalog.ProviderEntry
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.http.RequestAuth
import dev.kestrel.providers.styles.AnthropicAdapter
import dev.kestrel.providers.styles.MantleRouting
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl

/** Where a profile's model list comes from (design D13). It runs only when a profile is created or the owner taps "Refresh models". */
object Discoveries {
    /**
     * The list of a provider whose catalog entry says REFRESHABLE or RUNTIME, by its style: `GET <base>/models` for Chat
     * Completions and Responses, `GET <base>/v1/models` for Anthropic Messages, `GET <base>/models` for Gemini (the models
     * that can `generateContent`), and Ollama's tags for Ollama Cloud. Bedrock lists its models through its control plane
     * in [region], and Mantle at `https://bedrock-mantle.<region>.api.aws/v1/models` whatever base it gets. Null for a
     * STATIC provider, for Vertex and Azure (the owner types the model), and for a Bedrock or Mantle profile without a
     * region. Plan 4C never calls it for Copilot, the ChatGPT plan or xAI's proxy, whose lists it builds itself.
     */
    fun forProvider(provider: ProviderEntry, baseUrl: HttpUrl, region: String?, auth: RequestAuth, http: ProviderHttp): ModelDiscovery? =
        when {
            provider.id == BEDROCK -> region?.let { BedrockModels(it, auth, http) }
            provider.id == MANTLE -> region?.let { OpenAiModels(MantleRouting.modelsBase(it), auth, http) }
            provider.discovery == Discovery.STATIC -> null
            else -> when (provider.style) {
                RequestStyle.CHAT_COMPLETIONS, RequestStyle.RESPONSES -> OpenAiModels(baseUrl, auth, http)
                RequestStyle.ANTHROPIC -> AnthropicModelList(baseUrl, auth, http)
                RequestStyle.GEMINI -> GeminiModelList(baseUrl, auth, http)
                RequestStyle.OLLAMA -> LocalServerDiscovery(LocalServerKind.OLLAMA, baseUrl, auth, http)
                else -> null
            }
        }

    /** The list of a local server of [kind] at [baseUrl], whose paths start at the address's origin (design D11). */
    fun forLocalServer(kind: LocalServerKind, baseUrl: HttpUrl, auth: RequestAuth, http: ProviderHttp): ModelDiscovery =
        LocalServerDiscovery(kind, baseUrl, auth, http)

    private const val BEDROCK = "amazon-bedrock"
    private const val MANTLE = "amazon-bedrock-mantle"
}

private val LIST_JSON = Json { ignoreUnknownKeys = true }

/** Reads one page of a list, or fails with Malformed. */
private suspend fun ProviderHttp.listPage(url: HttpUrl, auth: RequestAuth, headers: Map<String, String> = emptyMap()): JsonObject {
    val text = send(HttpRequest("GET", url, mapOf("accept" to "application/json") + headers), auth).text()
    return try {
        LIST_JSON.parseToJsonElement(text) as? JsonObject
    } catch (e: SerializationException) {
        null
    } ?: throw ProviderException(ProviderError.Malformed)
}

private fun JsonElement?.rows(): List<JsonObject> = (this as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.whole(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.toInt()

private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

private fun JsonObject.texts(key: String): List<String>? = (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }

/**
 * OpenAI's `GET <base>/models` (`data[].id`), with the fields that OpenRouter-like lists add: `name`, `context_length`,
 * `architecture.input_modalities`, `supported_parameters` (`tools`, `reasoning`) and `top_provider.max_completion_tokens`.
 */
private class OpenAiModels(private val base: HttpUrl, private val auth: RequestAuth, private val http: ProviderHttp) : ModelDiscovery {
    override suspend fun list(): List<DiscoveredModel> =
        http.listPage(base.newBuilder().addPathSegment("models").build(), auth)["data"].rows().mapNotNull { row ->
            val id = row.text("id") ?: return@mapNotNull null
            val parameters = row.texts("supported_parameters")
            val modalities = (row["architecture"] as? JsonObject)?.texts("input_modalities")
            DiscoveredModel(
                id = id,
                name = row.text("name") ?: id,
                input = if (modalities?.contains("image") == true) setOf(InputKind.TEXT, InputKind.IMAGE) else setOf(InputKind.TEXT),
                reasoning = parameters?.contains("reasoning") == true,
                contextWindow = row.whole("context_length") ?: row.whole("context_window"),
                maxTokens = (row["top_provider"] as? JsonObject)?.whole("max_completion_tokens"),
                tools = parameters?.contains("tools"),
            )
        }
}

/**
 * Anthropic's `GET <base>/v1/models` (Anthropic's "List Models" reference): `data[]` with `id`, `display_name`,
 * `max_input_tokens`, `max_tokens` and `capabilities` (`image_input`, `thinking`, `effort`), read page by page with
 * `after_id` while `has_more` is true.
 */
private class AnthropicModelList(private val base: HttpUrl, private val auth: RequestAuth, private val http: ProviderHttp) : ModelDiscovery {
    override suspend fun list(): List<DiscoveredModel> {
        val models = mutableListOf<DiscoveredModel>()
        var after: String? = null
        do {
            val url = base.newBuilder().addPathSegments("v1/models").addQueryParameter("limit", "1000")
                .apply { after?.let { addQueryParameter("after_id", it) } }.build()
            val page = http.listPage(url, auth, mapOf("anthropic-version" to AnthropicAdapter.VERSION))
            for (row in page["data"].rows()) {
                val id = row.text("id") ?: continue
                val capabilities = row["capabilities"] as? JsonObject
                val effort = (capabilities?.get("effort") as? JsonObject)?.takeIf { it.flag("supported") == true }
                models += DiscoveredModel(
                    id = id,
                    name = row.text("display_name") ?: id,
                    input = if (capabilities.supported("image_input")) setOf(InputKind.TEXT, InputKind.IMAGE) else setOf(InputKind.TEXT),
                    reasoning = capabilities.supported("thinking"),
                    reasoningEfforts = effort?.let { levels -> EFFORTS.filter { levels.supported(it) } },
                    contextWindow = row.whole("max_input_tokens"),
                    maxTokens = row.whole("max_tokens"),
                )
            }
            after = page.text("last_id")?.takeIf { page.flag("has_more") == true }
        } while (after != null)
        return models
    }

    private fun JsonObject?.supported(name: String): Boolean = (this?.get(name) as? JsonObject)?.flag("supported") == true

    private companion object {
        val EFFORTS = listOf("low", "medium", "high", "xhigh", "max")
    }
}

/**
 * Gemini's `GET <base>/models` (Google's `models.list` reference): `models[]` with `name` (`models/<id>`), `displayName`,
 * `inputTokenLimit`, `outputTokenLimit`, `supportedGenerationMethods` and `thinking`, read page by page with `pageToken`.
 * Only the models that can `generateContent` are listed.
 */
private class GeminiModelList(private val base: HttpUrl, private val auth: RequestAuth, private val http: ProviderHttp) : ModelDiscovery {
    override suspend fun list(): List<DiscoveredModel> {
        val models = mutableListOf<DiscoveredModel>()
        var token: String? = null
        do {
            val url = base.newBuilder().addPathSegment("models").addQueryParameter("pageSize", "1000")
                .apply { token?.let { addQueryParameter("pageToken", it) } }.build()
            val page = http.listPage(url, auth)
            for (row in page["models"].rows()) {
                if (row.texts("supportedGenerationMethods")?.contains("generateContent") != true) continue
                val id = row.text("name")?.removePrefix("models/") ?: continue
                models += DiscoveredModel(
                    id = id,
                    name = row.text("displayName") ?: id,
                    reasoning = row.flag("thinking") == true,
                    contextWindow = row.whole("inputTokenLimit"),
                    maxTokens = row.whole("outputTokenLimit"),
                )
            }
            token = page.text("nextPageToken")?.takeIf { it.isNotEmpty() }
        } while (token != null)
        return models
    }
}
