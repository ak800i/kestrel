package dev.kestrel.providers.discovery

import dev.kestrel.providers.catalog.InputKind
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.http.RequestAuth
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Bedrock's model list from its control plane `https://bedrock.<region>.amazonaws.com` (design D13, research r34), with
 * OpenClaw's filters: a foundation model is offered when it streams, writes text, is ACTIVE, is not the Mythos preview,
 * and can run on demand; an inference profile when it is ACTIVE and not for the Mythos preview (OpenClaw's
 * `extensions/amazon-bedrock/discovery.ts`), with the input kinds of its base model. Profiles are
 * read page by page through `nextToken`, and `global.` profiles come first.
 */
internal class BedrockModels(region: String, private val auth: RequestAuth, private val http: ProviderHttp) : ModelDiscovery {
    private val base: HttpUrl

    init {
        require(Regex("^[a-z0-9-]+$").matches(region)) { "A region must match ^[a-z0-9-]+$" }
        base = "https://bedrock.$region.amazonaws.com".toHttpUrl()
    }

    override suspend fun list(): List<DiscoveredModel> {
        val foundations = objects(get(base.newBuilder().addPathSegment("foundation-models").build())["modelSummaries"])
        val inputs = foundations.associate { it.string("modelId").orEmpty() to it.inputs() }
        val models = foundations.filter { model ->
            model.boolean("responseStreamingSupported") == true &&
                model.strings("outputModalities").any { it.equals("TEXT", ignoreCase = true) } &&
                (model["modelLifecycle"] as? JsonObject)?.string("status").equals("ACTIVE", ignoreCase = true) &&
                model.string("modelId")?.contains(MYTHOS_PREVIEW) != true &&
                model.strings("inferenceTypesSupported").let { it.isEmpty() || "ON_DEMAND" in it }
        }.mapNotNull { model ->
            val id = model.string("modelId") ?: return@mapNotNull null
            DiscoveredModel(id = id, name = model.string("modelName") ?: id, input = model.inputs())
        }
        val profiles = mutableListOf<DiscoveredModel>()
        var token: String? = null
        do {
            val url = base.newBuilder().addPathSegment("inference-profiles").addQueryParameter("maxResults", "1000")
                .apply { token?.let { addQueryParameter("nextToken", it) } }.build()
            val page = get(url)
            for (profile in objects(page["inferenceProfileSummaries"])) {
                if (profile.string("status") != "ACTIVE") continue
                val id = profile.string("inferenceProfileId") ?: continue
                val baseModel = objects(profile["models"]).firstOrNull()?.string("modelArn")
                    ?.let { Regex("foundation-model/(.+)$").find(it)?.groupValues?.get(1) }
                    ?: id.replace(Regex("^(?:us|eu|ap|apac|au|jp|global)\\."), "")
                if (MYTHOS_PREVIEW in baseModel || MYTHOS_PREVIEW in id) continue
                profiles += DiscoveredModel(id = id, name = profile.string("inferenceProfileName") ?: id, input = inputs[baseModel] ?: setOf(InputKind.TEXT))
            }
            token = page.string("nextToken")?.takeIf { it.isNotEmpty() }
        } while (token != null)
        return profiles.sortedBy { if (it.id.startsWith("global.")) 0 else 1 } + models
    }

    private suspend fun get(url: HttpUrl): JsonObject {
        val text = http.send(HttpRequest("GET", url, mapOf("accept" to "application/json")), auth).text()
        return try {
            JSON.parseToJsonElement(text) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: throw ProviderException(ProviderError.Malformed)
    }

    private fun JsonObject.inputs(): Set<InputKind> =
        if (strings("inputModalities").any { it.equals("IMAGE", ignoreCase = true) }) setOf(InputKind.TEXT, InputKind.IMAGE) else setOf(InputKind.TEXT)

    private fun objects(element: Any?): List<JsonObject> = (element as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    private fun JsonObject.strings(key: String): List<String> = (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()

    override fun toString(): String = "BedrockModels"

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        const val MYTHOS_PREVIEW = "claude-mythos-preview"
    }
}
