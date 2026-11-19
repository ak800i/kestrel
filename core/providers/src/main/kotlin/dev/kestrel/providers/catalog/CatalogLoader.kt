package dev.kestrel.providers.catalog

import kotlinx.serialization.json.Json

/**
 * Merges `catalog/providers.json` with `catalog/overrides.json` into one [Catalog] at runtime (design D2), so that a
 * change of the overrides needs no new sync. In order: it drops excluded providers and choices; applies the per-model
 * corrections and prices to the synced rows (a row whose compat says `supportsTools: false` gets JSON actions, design D7);
 * completes and adds the providers of the overrides; copies rows with `modelsFrom`, after the prices, so that Vertex's
 * Gemini rows carry Gemini's prices; attaches each choice's settings and adds Kestrel's own choices; and puts Copilot's
 * prices on the `github-copilot` rows.
 */
object CatalogLoader {
    const val COPILOT = "github-copilot"

    private val json = Json { ignoreUnknownKeys = true }

    fun load(providersJson: String, overridesJson: String): Catalog {
        val file = json.decodeFromString(CatalogFile.serializer(), providersJson)
        val overrides = json.decodeFromString(Overrides.serializer(), overridesJson)
        require(file.schema == 1 && overrides.schema == 1) { "Unknown catalog schema" }
        val excluded = overrides.excludeProviders.map { it.id }.toSet()
        val excludedChoices = overrides.excludeAuthChoices.map { it.provider to it.choiceId }.toSet()
        val corrections = overrides.models.associateBy { it.provider to it.model }
        val byId = linkedMapOf<String, ProviderEntry>()

        for (provider in file.providers) {
            if (provider.id in excluded) continue
            byId[provider.id] = provider.copy(
                authChoices = provider.authChoices.filter { (provider.id to it.choiceId) !in excludedChoices },
                models = provider.models.map { correct(it, corrections[provider.id to it.id]) },
            )
        }
        for (change in overrides.providers) {
            if (change.id in excluded) continue
            val base = byId[change.id] ?: ProviderEntry(
                id = change.id,
                plugin = change.plugin ?: change.id,
                label = change.label ?: change.id,
                style = null,
                baseUrl = null,
                hosts = emptyList(),
                authChoices = emptyList(),
                models = emptyList(),
            )
            val added = change.models.map { correct(it, corrections[change.id to it.id]) }
            byId[change.id] = base.copy(
                label = change.label ?: base.label,
                plugin = change.plugin ?: base.plugin,
                style = change.style ?: base.style,
                baseUrl = change.baseUrl ?: base.baseUrl,
                hosts = (base.hosts + change.hosts + listOfNotNull(hostOf(change.baseUrl)?.takeIf { '{' !in it })).distinct(),
                regions = change.regions.ifEmpty { base.regions },
                discovery = change.discovery ?: base.discovery,
                defaultModel = change.defaultModel ?: base.defaultModel,
                utilityModel = change.utilityModel ?: base.utilityModel,
                models = base.models.filter { row -> added.none { it.id == row.id } } + added,
            )
        }
        for (change in overrides.providers) {
            val source = change.modelsFrom?.let { byId[it] } ?: continue
            val target = byId[change.id] ?: continue
            val copied = source.models
                .filter { row -> target.models.none { it.id == row.id } }
                .map { it.copy(style = it.style ?: source.style, baseUrl = null) }
            byId[change.id] = target.copy(models = target.models + copied)
        }
        val settings = overrides.choices.groupBy { it.provider }
        for ((id, provider) in byId.entries.toList()) {
            val own = settings[id].orEmpty()
            val attached = provider.authChoices.map { choice ->
                val match = own.firstOrNull { it.choiceId == choice.choiceId }
                choice.copy(label = match?.label ?: choice.label, settings = match)
            }
            val added = own.filter { s -> provider.authChoices.none { it.choiceId == s.choiceId } }
                .map { AuthChoice(choiceId = it.choiceId, method = KESTREL_METHOD, label = it.label ?: it.choiceId, settings = it) }
            byId[id] = provider.copy(authChoices = attached + added)
        }
        byId[COPILOT]?.let { copilot ->
            byId[COPILOT] = copilot.copy(
                models = copilot.models.map { row -> row.copy(prices = copilotPrice(overrides.copilot, row.id, row.name)?.prices.orEmpty()) },
            )
        }
        return Catalog(
            openclawCommit = file.openclawCommit,
            providers = byId.values.map { it.copy(models = it.models.sortedBy(ModelEntry::id)) }.sortedBy(ProviderEntry::id),
            localServers = overrides.localServers,
            overrides = overrides,
        )
    }

    /** The Copilot price of a model, by its ID first and then by its normalised name (design D12). */
    fun copilotPrice(copilot: CopilotOverrides, modelId: String, modelName: String?): CopilotPrice? =
        copilot.models.firstOrNull { it.id == modelId }
            ?: copilot.models.firstOrNull { entry ->
                val name = normalise(entry.name)
                name == normalise(modelId) || (modelName != null && name == normalise(modelName))
            }

    /** Lower case, with spaces, hyphens and underscores removed: "GPT-6.1 Terra" and "gpt-6.1-terra" both give "gpt6.1terra". */
    fun normalise(name: String): String = name.lowercase().filterNot { it == ' ' || it == '-' || it == '_' }

    /** The host of a URL that may hold `{region}`, which HttpUrl cannot parse. */
    fun hostOf(url: String?): String? =
        url?.substringAfter("://", "")?.substringBefore('/')?.substringBefore('?')?.substringBefore(':')?.lowercase()
            ?.takeIf { it.isNotEmpty() }

    private fun correct(model: ModelEntry, change: ModelOverride?): ModelEntry =
        model.copy(
            style = change?.style ?: model.style,
            toolMode = change?.toolMode ?: if (model.compat.supportsTools == false) ToolMode.JSON else model.toolMode,
            prices = change?.prices ?: model.prices,
        )

    private const val KESTREL_METHOD = "kestrel"
}
