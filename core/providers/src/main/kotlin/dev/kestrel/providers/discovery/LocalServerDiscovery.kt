package dev.kestrel.providers.discovery

import dev.kestrel.providers.catalog.InputKind
import dev.kestrel.providers.catalog.LocalServerKind
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.http.RequestAuth
import dev.kestrel.providers.styles.OllamaAdapter
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl

/**
 * The model lists of the local servers (design D11, research r40), read from the address's origin whatever path the
 * owner typed: Ollama's `/api/tags` and `/api/show` (its `capabilities` and `model_info`), LM Studio's `/api/v1/models`
 * (`capabilities.vision` and `trained_for_tool_use`), llama.cpp's `/v1/models` and `/props`, and `/v1/models` for vLLM,
 * SGLang, oMLX and LiteLLM. Where a server reports nothing, [DiscoveredModel.tools] stays null and the profile page asks
 * the owner. A model that reports no tool support gets `tools = false`, so that it uses JSON actions (design D7). An
 * Ollama model whose capabilities lack `completion` (an embedding model) is left out, as OpenClaw does.
 */
internal class LocalServerDiscovery(
    private val kind: LocalServerKind,
    baseUrl: HttpUrl,
    private val auth: RequestAuth,
    private val http: ProviderHttp,
) : ModelDiscovery {
    private val origin = OllamaAdapter.origin(baseUrl)

    override suspend fun list(): List<DiscoveredModel> = when (kind) {
        LocalServerKind.OLLAMA -> ollama()
        LocalServerKind.LM_STUDIO -> lmStudio()
        LocalServerKind.LLAMA_CPP -> llamaCpp()
        LocalServerKind.VLLM, LocalServerKind.SGLANG, LocalServerKind.OMLX, LocalServerKind.LITELLM -> openAiList()
    }

    private suspend fun ollama(): List<DiscoveredModel> =
        objects(get("api/tags")["models"]).mapNotNull { row ->
            val name = row.string("name") ?: row.string("model") ?: return@mapNotNull null
            val show = try {
                read(http.send(request("POST", "api/show", buildJsonObject { put("model", name) }), auth).text())
            } catch (e: ProviderException) {
                null
            }
            val capabilities = (show?.get("capabilities") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
            if (capabilities != null && "completion" !in capabilities) return@mapNotNull null
            val context = (show?.get("model_info") as? JsonObject)?.entries
                ?.firstOrNull { it.key.endsWith(".context_length") }?.value?.let { (it as? JsonPrimitive)?.longOrNull?.toInt() }
            DiscoveredModel(
                id = name,
                name = name,
                input = if (capabilities?.contains("vision") == true) setOf(InputKind.TEXT, InputKind.IMAGE) else setOf(InputKind.TEXT),
                reasoning = capabilities?.contains("thinking") == true,
                contextWindow = context,
                tools = capabilities?.contains("tools"),
            )
        }

    private suspend fun lmStudio(): List<DiscoveredModel> =
        objects(get("api/v1/models")["models"]).filter { it.string("type") == "llm" }.mapNotNull { row ->
            val id = row.string("key") ?: return@mapNotNull null
            val capabilities = row["capabilities"] as? JsonObject
            DiscoveredModel(
                id = id,
                name = row.string("display_name") ?: id,
                input = if (capabilities?.boolean("vision") == true) setOf(InputKind.TEXT, InputKind.IMAGE) else setOf(InputKind.TEXT),
                reasoning = capabilities?.get("reasoning") is JsonObject,
                contextWindow = row.number("max_context_length"),
                tools = capabilities?.boolean("trained_for_tool_use"),
            )
        }

    private suspend fun llamaCpp(): List<DiscoveredModel> {
        val rows = objects(get("v1/models")["data"])
        val props = try {
            get("props")
        } catch (e: ProviderException) {
            null
        }
        val tools = (props?.get("chat_template_caps") as? JsonObject)?.boolean("supports_tool_calls")
        val vision = (props?.get("modalities") as? JsonObject)?.boolean("vision") == true
        val context = (props?.get("default_generation_settings") as? JsonObject)?.number("n_ctx")
        return rows.mapNotNull { row ->
            val id = row.string("id") ?: return@mapNotNull null
            val modalities = ((row["architecture"] as? JsonObject)?.get("input_modalities") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
            DiscoveredModel(
                id = id,
                name = id,
                input = if (vision || modalities?.contains("image") == true) setOf(InputKind.TEXT, InputKind.IMAGE) else setOf(InputKind.TEXT),
                contextWindow = context,
                tools = tools,
            )
        }
    }

    private suspend fun openAiList(): List<DiscoveredModel> =
        objects(get("v1/models")["data"]).mapNotNull { row ->
            val id = row.string("id") ?: return@mapNotNull null
            DiscoveredModel(
                id = id,
                name = id,
                contextWindow = CONTEXT_FIELDS.firstNotNullOfOrNull { row.number(it) },
            )
        }

    private suspend fun get(path: String): JsonObject = read(http.send(request("GET", path, null), auth).text())

    private fun request(method: String, path: String, body: JsonObject?): HttpRequest =
        HttpRequest(
            method = method,
            url = origin.newBuilder().addPathSegments(path).build(),
            headers = mapOf("accept" to "application/json"),
            body = body?.toString()?.encodeToByteArray(),
            contentType = body?.let { "application/json" },
        )

    private fun read(text: String): JsonObject =
        try {
            JSON.parseToJsonElement(text) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: throw ProviderException(ProviderError.Malformed)

    private fun objects(element: JsonElement?): List<JsonObject> = (element as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    private fun JsonObject.number(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.toInt()

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        val CONTEXT_FIELDS = listOf("max_model_len", "context_length", "context_window", "context_size")
    }
}
