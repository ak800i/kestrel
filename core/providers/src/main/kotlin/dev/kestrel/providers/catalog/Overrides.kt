package dev.kestrel.providers.catalog

import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.cost.Price
import dev.kestrel.providers.http.KeyHeader
import kotlinx.serialization.Serializable

/** The content of `catalog/overrides.json` (schema 1), written by hand (design D2 and "Shared names"). */
@Serializable
data class Overrides(
    val schema: Int,
    val excludeProviders: List<Exclusion>,
    val excludeAuthChoices: List<AuthExclusion>,
    val choices: List<ChoiceOverride>,
    val providers: List<ProviderOverride>,
    val localServers: List<LocalServer>,
    val models: List<ModelOverride>,
    val freeModels: List<ModelRef>,
    val unknownPrice: Price,
    val copilot: CopilotOverrides,
)

@Serializable
data class Exclusion(val id: String, val reason: String)

@Serializable
data class AuthExclusion(val provider: String, val choiceId: String, val reason: String)

@Serializable
data class ModelRef(val provider: String, val model: String)

@Serializable
data class TermsLink(val label: String, val url: String)

/**
 * The settings that depend on the sign-in rather than on the provider (design D9, D18 and D21). A choice that only
 * Kestrel has (for example `chatgpt-plan` or `bedrock-api-key`) is a ChoiceOverride whose [choiceId] no manifest has.
 * [keyHeader] null means the style's default (design D4). [borrowedIdentity] names the switch of a borrowed sign-in;
 * [defaultOn] is its state until the owner changes it.
 */
@Serializable
data class ChoiceOverride(
    val provider: String,
    val choiceId: String,
    val method: AuthMethod,
    val label: String? = null,
    val baseUrl: String? = null,
    val style: RequestStyle? = null,
    val chatgptPlan: Boolean = false,
    val planUsage: Boolean = false,
    val keyHeader: KeyHeader? = null,
    val identityHeaders: Map<String, String> = emptyMap(),
    val refreshHosts: List<String> = emptyList(),
    val customBaseUrl: Boolean = false,
    val borrowedIdentity: String? = null,
    val offered: Boolean = true,
    val defaultOn: Boolean = true,
    val termsLinks: List<TermsLink> = emptyList(),
)

/**
 * Completes a provider that the sync wrote, or adds one that it did not ([plugin] then names the plugin). [baseUrl] and
 * the regions' URLs may hold `{region}` or `{location}`; [modelsFrom] copies another provider's rows (Vertex from
 * `google`); [models] are added beside the synced and discovered rows, and replace a synced row with the same ID;
 * [defaultModel] and [utilityModel] name the default models of a provider that the overrides complete.
 */
@Serializable
data class ProviderOverride(
    val id: String,
    val label: String? = null,
    val plugin: String? = null,
    val style: RequestStyle? = null,
    val baseUrl: String? = null,
    val hosts: List<String> = emptyList(),
    val regions: List<Region> = emptyList(),
    val discovery: Discovery? = null,
    val modelsFrom: String? = null,
    val models: List<ModelEntry> = emptyList(),
    val defaultModel: String? = null,
    val utilityModel: String? = null,
)

/** A correction of one synced model: its style, its tool mode (design D7) or its prices (design D14), with where and when the prices were copied. */
@Serializable
data class ModelOverride(
    val provider: String,
    val model: String,
    val style: RequestStyle? = null,
    val toolMode: ToolMode? = null,
    val prices: List<Price>? = null,
    val pricesCopiedOn: String? = null,
    val pricesSource: String? = null,
)

/**
 * GitHub Copilot's prices, copied from GitHub's pricing page on [pricesCopiedOn] (design D12), its default models by name
 * in order of preference, and its two header sets.
 */
@Serializable
data class CopilotOverrides(
    val pricesCopiedOn: String,
    val pricesSource: String,
    val models: List<CopilotPrice>,
    val lightweight: List<String>,
    val versatile: List<String>,
    val runtimeHeaders: Map<String, String>,
    val ideHeaders: Map<String, String>,
)

/** A Copilot model's prices by display name, and by ID where the synced manifest has it. */
@Serializable
data class CopilotPrice(val name: String, val id: String? = null, val prices: List<Price>)
