package dev.kestrel.providers.discovery

import dev.kestrel.providers.catalog.Discovery
import dev.kestrel.providers.catalog.InputKind
import dev.kestrel.providers.catalog.LocalServerKind
import dev.kestrel.providers.catalog.ProviderEntry
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.http.RequestAuth
import dev.kestrel.providers.styles.AnthropicAdapter
import dev.kestrel.providers.styles.GeminiAdapter
import dev.kestrel.providers.styles.MantleRouting
import dev.kestrel.providers.styles.OllamaAdapter
import dev.kestrel.providers.styles.Wire
import dev.kestrel.providers.styles.Wire.bool
import dev.kestrel.providers.styles.Wire.long
import dev.kestrel.providers.styles.Wire.objects
import dev.kestrel.providers.styles.Wire.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    /** The list of a local server of [kind] at [baseUrl], read from [localServerBase] (design D11). */
    fun forLocalServer(kind: LocalServerKind, baseUrl: HttpUrl, auth: RequestAuth, http: ProviderHttp): ModelDiscovery =
        LocalServerDiscovery(kind, baseUrl, auth, http)

    /**
     * The one base of a local server at the owner's [address] (design D11), for its "Check" and for its requests alike.
     * For Ollama it is the address without a trailing `/v1` (OllamaAdapter's rule, which keeps a path prefix). For the
     * servers that Kestrel reaches with Chat Completions (LM Studio, vLLM, SGLang, llama.cpp, oMLX and LiteLLM) it is the
     * address without a trailing `/v1`, plus `/v1`, as OpenClaw's LM Studio `resolveLmstudioInferenceBase` does: so
     * `http://host:1234` and `http://host:1234/v1` both give `http://host:1234/v1`, and `https://host/litellm` gives
     * `https://host/litellm/v1`.
     */
    fun localServerBase(kind: LocalServerKind, address: HttpUrl): HttpUrl {
        val root = OllamaAdapter.origin(address)
        return if (kind == LocalServerKind.OLLAMA) root else root.newBuilder().addPathSegment("v1").build()
    }

    private const val BEDROCK = "amazon-bedrock"
    private const val MANTLE = "amazon-bedrock-mantle"
}

/** The most pages that a paged list reads, as BedrockModels reads its profiles. */
private const val MAX_PAGES = 50

/** Reads one page of a list, or fails with Malformed. */
private suspend fun ProviderHttp.listPage(url: HttpUrl, auth: RequestAuth, headers: Map<String, String> = emptyMap()): JsonObject =
    Wire.parse(send(HttpRequest("GET", url, mapOf("accept" to "application/json") + headers), auth).text())

/** A whole number that fits an Int, or null, so that a value above Int.MAX_VALUE does not become a wrong one. */
private fun JsonObject.whole(key: String): Int? = long(key)?.takeIf { it in 1..Int.MAX_VALUE }?.toInt()

private fun JsonObject.texts(key: String): List<String>? = (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }

/**
 * OpenAI's `GET <base>/models` (`data[].id`), with the fields that OpenRouter-like lists add: `name`, `context_length`,
 * `architecture.input_modalities`, `supported_parameters` (`tools`, `reasoning`) and `top_provider.max_completion_tokens`.
 * OpenAI's own list also holds models that cannot chat (embeddings, speech, images, moderation and realtime audio);
 * their well-known families are left out.
 */
private class OpenAiModels(private val base: HttpUrl, private val auth: RequestAuth, private val http: ProviderHttp) : ModelDiscovery {
    override suspend fun list(): List<DiscoveredModel> =
        http.listPage(base.newBuilder().addPathSegment("models").build(), auth)["data"].objects().mapNotNull { row ->
            val id = row.str("id") ?: return@mapNotNull null
            if (NOT_CHAT.containsMatchIn(id.lowercase())) return@mapNotNull null
            val parameters = row.texts("supported_parameters")
            val modalities = (row["architecture"] as? JsonObject)?.texts("input_modalities")
            DiscoveredModel(
                id = id,
                name = row.str("name") ?: id,
                input = if (modalities?.contains("image") == true) setOf(InputKind.TEXT, InputKind.IMAGE) else setOf(InputKind.TEXT),
                reasoning = parameters?.contains("reasoning") == true,
                contextWindow = row.whole("context_length") ?: row.whole("context_window"),
                maxTokens = (row["top_provider"] as? JsonObject)?.whole("max_completion_tokens"),
                tools = parameters?.contains("tools"),
            )
        }

    private companion object {
        val NOT_CHAT = Regex(
            "^(?:text-embedding-|text-moderation-|omni-moderation-|whisper-|tts-|dall-e-|gpt-image-|sora-|davinci-|babbage-)|realtime|transcribe|-tts(?:-|$)",
        )
    }
}

/**
 * Anthropic's `GET <base>/v1/models` (Anthropic's "List Models" reference): `data[]` with `id`, `display_name`,
 * `max_input_tokens`, `max_tokens` and `capabilities` (`image_input`, `thinking`, `effort`), read page by page with
 * `after_id` while `has_more` is true, at most [MAX_PAGES] pages and until a `last_id` repeats.
 */
private class AnthropicModelList(private val base: HttpUrl, private val auth: RequestAuth, private val http: ProviderHttp) : ModelDiscovery {
    override suspend fun list(): List<DiscoveredModel> {
        val models = mutableListOf<DiscoveredModel>()
        val used = mutableSetOf<String>()
        var pages = 0
        var after: String? = null
        do {
            val url = base.newBuilder().addPathSegments("v1/models").addQueryParameter("limit", "1000")
                .apply { after?.let { addQueryParameter("after_id", it) } }.build()
            val page = http.listPage(url, auth, mapOf("anthropic-version" to AnthropicAdapter.VERSION))
            pages++
            for (row in page["data"].objects()) {
                val id = row.str("id") ?: continue
                val capabilities = row["capabilities"] as? JsonObject
                val effort = (capabilities?.get("effort") as? JsonObject)?.takeIf { it.bool("supported") == true }
                models += DiscoveredModel(
                    id = id,
                    name = row.str("display_name") ?: id,
                    input = if (capabilities.supported("image_input")) setOf(InputKind.TEXT, InputKind.IMAGE) else setOf(InputKind.TEXT),
                    reasoning = capabilities.supported("thinking"),
                    reasoningEfforts = effort?.let { levels -> EFFORTS.filter { levels.supported(it) } },
                    contextWindow = row.whole("max_input_tokens"),
                    maxTokens = row.whole("max_tokens"),
                )
            }
            // A `last_id` that came before would read the same pages again.
            after = page.str("last_id")?.takeIf { page.bool("has_more") == true && used.add(it) }
        } while (after != null && pages < MAX_PAGES)
        return models
    }

    private fun JsonObject?.supported(name: String): Boolean = (this?.get(name) as? JsonObject)?.bool("supported") == true

    private companion object {
        val EFFORTS = listOf("low", "medium", "high", "xhigh", "max")
    }
}

/**
 * Gemini's `GET <base>/models` (Google's `models.list` reference): `models[]` with `name` (`models/<id>`), `displayName`,
 * `inputTokenLimit`, `outputTokenLimit`, `supportedGenerationMethods` and `thinking`, read page by page with `pageToken`.
 * Only the models that can `generateContent` are listed. Pages stop after [MAX_PAGES] and when a token repeats, and a
 * key that Google refuses (`API_KEY_INVALID`) is the credential's `rejected`, as in the Gemini adapter.
 */
private class GeminiModelList(private val base: HttpUrl, private val auth: RequestAuth, private val http: ProviderHttp) : ModelDiscovery {
    override suspend fun list(): List<DiscoveredModel> {
        val models = mutableListOf<DiscoveredModel>()
        val used = mutableSetOf<String>()
        var pages = 0
        var token: String? = null
        do {
            val url = base.newBuilder().addPathSegment("models").addQueryParameter("pageSize", "1000")
                .apply { token?.let { addQueryParameter("pageToken", it) } }.build()
            val page = try {
                http.listPage(url, auth)
            } catch (e: ProviderException) {
                throw if (GeminiAdapter.isKeyRefused(e.error)) ProviderException(auth.rejected) else e
            }
            pages++
            for (row in page["models"].objects()) {
                if (row.texts("supportedGenerationMethods")?.contains("generateContent") != true) continue
                val id = row.str("name")?.removePrefix("models/") ?: continue
                models += DiscoveredModel(
                    id = id,
                    name = row.str("displayName") ?: id,
                    reasoning = row.bool("thinking") == true,
                    contextWindow = row.whole("inputTokenLimit"),
                    maxTokens = row.whole("outputTokenLimit"),
                )
            }
            token = page.str("nextPageToken")?.takeIf { it.isNotEmpty() && used.add(it) }
        } while (token != null && pages < MAX_PAGES)
        return models
    }
}
