package dev.kestrel.providers.catalog

import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.cost.Price
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * The provider catalog that Kestrel offers (spec 10.1 and 10.2): `catalog/providers.json`, which `:tools:catalog-sync`
 * generates from OpenClaw's manifests, merged at runtime with the hand-written `catalog/overrides.json` (design D2).
 */
@Serializable
data class Catalog(
    val openclawCommit: String,
    val providers: List<ProviderEntry>,
    val localServers: List<LocalServer>,
    val overrides: Overrides,
) {
    fun provider(id: String): ProviderEntry? = providers.firstOrNull { it.id == id }

    fun choice(providerId: String, choiceId: String): AuthChoice? =
        provider(providerId)?.authChoices?.firstOrNull { it.choiceId == choiceId }
}

/** The content of `catalog/providers.json` (schema 1), which the sync writes and the loader reads. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CatalogFile(
    @EncodeDefault val schema: Int = 1,
    val openclawCommit: String,
    val providers: List<ProviderEntry>,
    val skipped: List<Skipped> = emptyList(),
)

/** A plugin, provider or model that the sync left out, and why. */
@Serializable
data class Skipped(val plugin: String, val provider: String? = null, val model: String? = null, val reason: String)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ProviderEntry(
    val id: String,
    val plugin: String,
    val label: String,
    /** Null where the manifest names no style; overrides.json completes such providers (design D4). */
    val style: RequestStyle?,
    /** May hold `{region}` or `{location}`, which the profile fills in (design D13). */
    val baseUrl: String?,
    /**
     * The hosts of the base URL and of the models' base URLs, and after the merge the hosts that overrides.json adds
     * (Bedrock's control plane, for example); a host may hold `{region}` too.
     */
    val hosts: List<String>,
    val regions: List<Region> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    val defaultModel: String? = null,
    val utilityModel: String? = null,
    @EncodeDefault val discovery: Discovery = Discovery.STATIC,
    val authChoices: List<AuthChoice>,
    val models: List<ModelEntry>,
)

/** A region or location that the owner chooses, with its own base URL. */
@Serializable
data class Region(val id: String, val label: String, val baseUrl: String)

/** Whether a provider's models come only from the catalog, may be refreshed from its list, or come only from its list. */
@Serializable
enum class Discovery { STATIC, REFRESHABLE, RUNTIME }

/** A sign-in choice. [settings] is null for a choice that Kestrel does not offer. */
@Serializable
data class AuthChoice(
    val choiceId: String,
    /** OpenClaw's method name, or `kestrel` for a choice that only overrides.json has. */
    val method: String,
    val label: String,
    val hint: String? = null,
    val settings: ChoiceOverride? = null,
) {
    /** Whether Kestrel offers this choice for new profiles (before the borrowed identity's switch). */
    val offered: Boolean get() = settings?.offered == true
}

/** How the owner signs in with a choice (design D9). */
@Serializable
enum class AuthMethod { API_KEY, DEVICE_CODE, BROWSER, IMPORTED, SERVER_ADDRESS }

@Serializable
data class ModelEntry(
    val id: String,
    val name: String,
    /** Set when the model uses another style than its provider; for a Vertex model, its wire (GEMINI or ANTHROPIC). */
    val style: RequestStyle? = null,
    val baseUrl: String? = null,
    val input: Set<InputKind> = setOf(InputKind.TEXT),
    val reasoning: Boolean = false,
    val contextWindow: Int? = null,
    val maxTokens: Int? = null,
    /** Empty when Kestrel knows no price: PriceTable then uses the unknown price (design D14). */
    val prices: List<Price> = emptyList(),
    val compat: CompatFlags = CompatFlags(),
    val thinkingLevelMap: Map<String, String?> = emptyMap(),
    val status: ModelStatus = ModelStatus.AVAILABLE,
    val toolMode: ToolMode = ToolMode.NATIVE,
)

@Serializable
enum class InputKind { TEXT, IMAGE }

@Serializable
enum class ModelStatus { AVAILABLE, PREVIEW, DEPRECATED }

/** NATIVE sends tool definitions; JSON asks for a JSON action instead (design D7). */
@Serializable
enum class ToolMode { NATIVE, JSON }

/**
 * OpenClaw's compatibility flags of a model, named as in OpenClaw (design D5, research r39). Null means "not stated":
 * plan 4B's CompatDefaults then derives the value from the provider and its host.
 */
@Serializable
data class CompatFlags(
    val maxTokensField: String? = null,
    val supportsTools: Boolean? = null,
    val supportsStrictMode: Boolean? = null,
    val supportsStore: Boolean? = null,
    val supportsDeveloperRole: Boolean? = null,
    val supportsReasoningEffort: Boolean? = null,
    val supportedReasoningEfforts: List<String>? = null,
    val reasoningEffortMap: Map<String, String>? = null,
    val supportsTemperature: Boolean? = null,
    val supportsPromptCacheKey: Boolean? = null,
    val thinkingFormat: String? = null,
    val thinkingLevels: List<String>? = null,
    val defaultThinkingLevel: String? = null,
    val requiresReasoningContentOnAssistantMessages: Boolean? = null,
    val requiresStringContent: Boolean? = null,
    val strictMessageKeys: Boolean? = null,
    val requiresToolResultName: Boolean? = null,
    val requiresAssistantAfterToolResult: Boolean? = null,
    val requiresThinkingAsText: Boolean? = null,
    val supportsUsageInStreaming: Boolean? = null,
    val supportsJsonSchemaResponseFormat: Boolean? = null,
    val cacheControlFormat: String? = null,
)

/** A server that the owner runs and Kestrel connects to by its address (design D11). [id] is its provider's ID. */
@Serializable
data class LocalServer(val id: String, val label: String, val kind: LocalServerKind, val style: RequestStyle, val defaultUrl: String)

@Serializable
enum class LocalServerKind { OLLAMA, LM_STUDIO, VLLM, SGLANG, LLAMA_CPP, OMLX, LITELLM }
