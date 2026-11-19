package dev.kestrel.catalogsync

import dev.kestrel.providers.catalog.CatalogFile
import dev.kestrel.providers.catalog.CompatFlags
import dev.kestrel.providers.catalog.Discovery
import dev.kestrel.providers.catalog.InputKind
import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.catalog.ModelStatus
import dev.kestrel.providers.catalog.ProviderEntry
import dev.kestrel.providers.catalog.Skipped
import dev.kestrel.providers.catalog.AuthChoice
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.cost.Price
import dev.kestrel.providers.cost.PriceTier
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File

/** What one run of the sync produced: the JSON of `catalog/providers.json` and the counts that the script prints. */
class SyncResult(val json: String, val summary: List<String>)

/**
 * Generates `catalog/providers.json` from a checkout of OpenClaw (spec 10.2, design D2). It reads every
 * `extensions/<plugin>/openclaw.plugin.json` of [checkout] as JSON5, keeps the plugins whose `categories` hold "models",
 * and writes one entry per provider ID of `providers` that has a `modelCatalog.providers.<id>` entry or auth choices. It
 * reads no overrides and no `providerEndpoints`, so its result depends only on the checkout.
 */
object CatalogSync {
    private val COMMIT = Regex("^[0-9a-f]{40}$")

    /** OpenClaw's `api` values that Kestrel's eight styles cover (design D4); `pi-messages` and `google-interactions` are not. */
    private val STYLES = mapOf(
        "openai-completions" to RequestStyle.CHAT_COMPLETIONS,
        "openai-responses" to RequestStyle.RESPONSES,
        "openai-chatgpt-responses" to RequestStyle.RESPONSES,
        "github-copilot" to RequestStyle.RESPONSES,
        "anthropic-messages" to RequestStyle.ANTHROPIC,
        "google-generative-ai" to RequestStyle.GEMINI,
        "ollama" to RequestStyle.OLLAMA,
        "bedrock-converse-stream" to RequestStyle.BEDROCK,
        "google-vertex" to RequestStyle.VERTEX,
        "azure-openai-responses" to RequestStyle.AZURE,
    )

    @OptIn(ExperimentalSerializationApi::class)
    private val manifestJson = Json {
        isLenient = true
        allowComments = true
        allowTrailingComma = true
        ignoreUnknownKeys = true
    }

    private val outputJson = Json { prettyPrint = true }

    fun generate(checkout: File, commit: String): String = sync(checkout, commit).json

    fun sync(checkout: File, commit: String): SyncResult {
        require(COMMIT.matches(commit)) { "The commit must be a 40-character hash" }
        val manifests = checkout.resolve("extensions").listFiles().orEmpty()
            .map { it.resolve("openclaw.plugin.json") }
            .filter { it.isFile }
            .sortedBy { it.parentFile.name }
        require(manifests.isNotEmpty()) { "No manifests in ${checkout.resolve("extensions")}" }
        val providers = mutableListOf<ProviderEntry>()
        val skipped = mutableListOf<Skipped>()
        var modelsPlugins = 0
        var noPrice = 0
        for (file in manifests) {
            val manifest = parse(file)
            if (manifest.strings("categories").none { it == "models" }) continue
            modelsPlugins++
            val result = read(manifest, file.parentFile.name)
            providers += result.providers
            skipped += result.skipped
            noPrice += result.noPrice
        }
        // CatalogLoader would keep only the last of two entries with one ID, so a duplicate stops the sync instead.
        val duplicate = providers.groupBy { it.id }.filterValues { it.size > 1 }.keys.firstOrNull()
        check(duplicate == null) { "Two manifests define the provider $duplicate" }
        val catalog = CatalogFile(
            openclawCommit = commit,
            providers = providers.sortedBy { it.id }.map { it.copy(models = it.models.sortedBy(ModelEntry::id)) },
            skipped = skipped.sortedWith(compareBy({ it.plugin }, { it.provider.orEmpty() }, { it.model.orEmpty() })),
        )
        val json = outputJson.encodeToString(CatalogFile.serializer(), catalog) + "\n"
        val perStyle = catalog.providers.groupingBy { it.style?.name ?: "none" }.eachCount().toSortedMap()
        val summary = listOf(
            "Manifests read: ${manifests.size}",
            "Models plugins: $modelsPlugins",
            "Providers: ${catalog.providers.size}",
            "Models: ${catalog.providers.sumOf { it.models.size }}",
            "Providers per style: " + perStyle.entries.joinToString(", ") { "${it.key} ${it.value}" },
            "Models whose all-zero cost became no price: $noPrice",
            "Skipped: ${catalog.skipped.size}",
        )
        return SyncResult(json, summary)
    }

    private class Read(val providers: List<ProviderEntry>, val skipped: List<Skipped>, val noPrice: Int)

    private fun parse(file: File): JsonObject =
        try {
            manifestJson.parseToJsonElement(file.readText()) as? JsonObject
                ?: throw IllegalStateException("extensions/${file.parentFile.name}/${file.name} is not a JSON object")
        } catch (e: SerializationException) {
            throw IllegalStateException("Cannot parse extensions/${file.parentFile.name}/${file.name}", e)
        }

    private fun read(manifest: JsonObject, folder: String): Read {
        val plugin = manifest.string("id") ?: folder
        val catalog = manifest.obj("modelCatalog")
        val entries = catalog?.obj("providers")
        val discovery = catalog?.obj("discovery")
        val choices = manifest.array("providerAuthChoices").filterIsInstance<JsonObject>()
        val providers = mutableListOf<ProviderEntry>()
        val skipped = mutableListOf<Skipped>()
        var noPrice = 0
        for (id in manifest.strings("providers")) {
            val entry = entries?.obj(id)
            val own = choices.filter { it.string("provider") == id }
            if (entry == null && own.isEmpty()) continue
            val api = entry?.string("api")
            val style = api?.let { STYLES[it] }
            if (api != null && style == null) {
                skipped += Skipped(plugin, id, reason = "unsupported style $api")
                continue
            }
            val models = mutableListOf<ModelEntry>()
            for (row in entry?.array("models").orEmpty().filterIsInstance<JsonObject>()) {
                val modelId = row.string("id") ?: continue
                val rowApi = row.string("api")
                val reason = when {
                    row.obj("inference")?.boolean("chat") == false -> "not a chat model"
                    row.string("status") == "disabled" -> "disabled"
                    rowApi != null && rowApi !in STYLES -> "unsupported style $rowApi"
                    else -> null
                }
                if (reason != null) {
                    skipped += Skipped(plugin, id, modelId, reason)
                    continue
                }
                val cost = row.obj("cost")
                val prices = prices(cost)
                if (cost != null && prices.isEmpty()) noPrice++
                models += ModelEntry(
                    id = modelId,
                    name = row.string("name") ?: modelId,
                    style = rowApi?.let { STYLES.getValue(it) },
                    baseUrl = row.string("baseUrl"),
                    input = inputKinds(row.strings("input")),
                    reasoning = row.boolean("reasoning") ?: false,
                    contextWindow = row.int("contextWindow"),
                    maxTokens = row.int("maxTokens"),
                    prices = prices,
                    compat = row.obj("compat")?.let { manifestJson.decodeFromJsonElement(CompatFlags.serializer(), it) } ?: CompatFlags(),
                    thinkingLevelMap = row.obj("thinkingLevelMap")?.mapValues { (_, value) -> (value as? JsonPrimitive)?.takeIf { it.isString }?.content }
                        .orEmpty(),
                    status = when (row.string("status")) {
                        "preview" -> ModelStatus.PREVIEW
                        "deprecated" -> ModelStatus.DEPRECATED
                        else -> ModelStatus.AVAILABLE
                    },
                )
            }
            val baseUrl = entry?.string("baseUrl")
            providers += ProviderEntry(
                id = id,
                plugin = plugin,
                label = own.firstOrNull()?.string("groupLabel") ?: manifest.string("name") ?: id,
                style = style,
                baseUrl = baseUrl,
                hosts = (listOf(baseUrl) + models.map { it.baseUrl }).mapNotNull(::hostOf).distinct(),
                headers = entry?.obj("headers")?.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.content?.let { key to it } }?.toMap()
                    .orEmpty(),
                defaultModel = entry?.string("defaultModel"),
                utilityModel = entry?.string("defaultUtilityModel"),
                discovery = when (discovery?.string(id)) {
                    "refreshable" -> Discovery.REFRESHABLE
                    "runtime" -> Discovery.RUNTIME
                    else -> Discovery.STATIC
                },
                authChoices = own.mapNotNull { choice ->
                    val choiceId = choice.string("choiceId") ?: return@mapNotNull null
                    AuthChoice(
                        choiceId = choiceId,
                        method = choice.string("method") ?: "",
                        label = choice.string("choiceLabel") ?: choiceId,
                        hint = choice.string("choiceHint"),
                    )
                },
                models = models,
            )
        }
        return Read(providers, skipped, noPrice)
    }

    /** OpenClaw writes all-zero costs where it has no price (research r26, r28), so those become no price at all. */
    private fun prices(cost: JsonObject?): List<Price> {
        if (cost == null) return emptyList()
        val input = cost.double("input") ?: 0.0
        val output = cost.double("output") ?: 0.0
        if (input == 0.0 && output == 0.0) return emptyList()
        // A tier's range [a, b) that starts at a > 0 applies above a - 1 input tokens; the tier from 0 is the base price.
        val tiers = cost.array("tieredPricing").filterIsInstance<JsonObject>().mapNotNull { tier ->
            val from = (tier.array("range").firstOrNull() as? JsonPrimitive)?.doubleOrNull?.toLong() ?: return@mapNotNull null
            if (from <= 0) return@mapNotNull null
            PriceTier(
                aboveInputTokens = from - 1,
                input = tier.double("input") ?: input,
                output = tier.double("output") ?: output,
                cacheRead = tier.double("cacheRead"),
                cacheWrite = tier.double("cacheWrite"),
            )
        }
        return listOf(Price(input, output, cost.double("cacheRead"), cost.double("cacheWrite"), tiers))
    }

    private fun inputKinds(values: List<String>): Set<InputKind> {
        val kinds = values.mapNotNull {
            when (it) {
                "text" -> InputKind.TEXT
                "image" -> InputKind.IMAGE
                else -> null
            }
        }.toSet()
        return kinds.ifEmpty { setOf(InputKind.TEXT) }
    }

    private fun hostOf(url: String?): String? =
        url?.substringAfter("://", "")?.substringBefore('/')?.substringBefore('?')?.substringBefore(':')?.lowercase()
            ?.takeIf { it.isNotEmpty() }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.array(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.strings(key: String): List<String> =
        array(key).mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content }

    private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
}
