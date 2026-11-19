package dev.kestrel.providers.client

import dev.kestrel.providers.catalog.Catalog
import dev.kestrel.providers.catalog.CatalogLoader
import dev.kestrel.providers.catalog.CompatFlags
import dev.kestrel.providers.catalog.InputKind
import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.catalog.ProviderEntry
import dev.kestrel.providers.catalog.ToolMode
import dev.kestrel.providers.chat.ModelTarget
import dev.kestrel.providers.chat.RequestHeaders
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.chat.StyleOptions
import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.credentials.CredentialManager
import dev.kestrel.providers.discovery.DiscoveredModel
import dev.kestrel.providers.discovery.Discoveries
import dev.kestrel.providers.http.KeyHeader
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.profiles.ModelRole
import dev.kestrel.providers.profiles.Profile
import dev.kestrel.providers.profiles.ProfileHosts
import dev.kestrel.providers.profiles.ProfileModel
import dev.kestrel.providers.signin.SignInKind
import dev.kestrel.providers.signin.SignInRegistry
import dev.kestrel.providers.styles.AzureRouting
import dev.kestrel.providers.styles.MantleRouting
import dev.kestrel.providers.styles.VertexUrls
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** The targets of a provider whose requests need more than the catalog, such as Copilot's (design D12). */
fun interface ProviderTargets {
    suspend fun target(profile: Profile, role: ModelRole, entry: ModelEntry): ModelTarget
}

/**
 * Resolves a profile's model for a role into a [ModelTarget] (design D4, D13 and "Shared names"): the style's base URL from
 * [ProfileHosts.baseUrl], the one rule that the allowlist uses too; the key header from the profile's choice, or the
 * style's default; the choice's ChatGPT plan switch; Azure's wire and key header; Mantle through MantleRouting; Vertex at
 * the location and the key file's project; the manifest's and the choice's headers, and xAI's model override on the Grok
 * CLI proxy. [models] gives a profile's last model list; [special] resolves the providers that need more, by provider ID.
 */
class TargetResolver(
    private val catalog: Catalog,
    private val credentials: CredentialManager,
    private val models: suspend (profileId: String) -> List<DiscoveredModel> = { emptyList() },
    private val special: Map<String, ProviderTargets> = emptyMap(),
) {
    suspend fun resolve(profile: Profile, role: ModelRole): ModelTarget {
        val chosen = profile.model(role)
        val provider = catalog.provider(profile.providerId)
        val entry = ModelEntries.withOwnerChoices(ModelEntries.entryFor(provider, chosen.id, models(profile.id)), chosen)
        special[profile.providerId]?.let { return it.target(profile, role, entry) }
        return when {
            profile.localServer != null -> localServer(profile, entry)
            profile.providerId == MANTLE -> mantle(profile, entry)
            provider?.style == RequestStyle.VERTEX -> vertex(profile, entry)
            provider?.style == RequestStyle.AZURE -> azure(profile, chosen, entry)
            else -> plain(profile, provider, chosen, entry)
        }
    }

    private suspend fun plain(profile: Profile, provider: ProviderEntry?, chosen: ProfileModel, entry: ModelEntry): ModelTarget {
        val choice = catalog.choice(profile.providerId, profile.choiceId)?.settings
        val style = chosen.style ?: entry.style ?: choice?.style ?: provider?.style ?: throw ProviderException(ProviderError.NotAllowed)
        val ownBase = profile.baseUrl == null && choice?.baseUrl == null
        val base = entry.baseUrl?.takeIf { ownBase }?.toHttpUrlOrNull() ?: ProfileHosts.baseUrl(profile, catalog)
            ?: throw ProviderException(ProviderError.NotAllowed)
        val fixed = provider?.headers.orEmpty() + choice?.identityHeaders.orEmpty()
        // The Grok CLI proxy serves the model that this header names (research r31); xAI's API keys never send it.
        val headers = if (base.host == GROK_PROXY) fixed + ("x-grok-model-override" to entry.id) else fixed
        return ModelTarget(
            providerId = profile.providerId,
            style = style,
            baseUrl = base,
            model = entry,
            auth = credentials.auth(profile, choice?.keyHeader ?: defaultKeyHeader(style)),
            headers = fixedHeaders(headers),
            options = StyleOptions(chatgptPlan = choice?.chatgptPlan == true),
        )
    }

    private suspend fun localServer(profile: Profile, entry: ModelEntry): ModelTarget {
        val style = catalog.localServers.firstOrNull { it.kind == profile.localServer }?.style ?: RequestStyle.CHAT_COMPLETIONS
        val address = profile.baseUrl?.toHttpUrlOrNull() ?: throw ProviderException(ProviderError.NotAllowed)
        val base = Discoveries.localServerBase(profile.localServer ?: throw ProviderException(ProviderError.NotAllowed), address)
        return ModelTarget(profile.providerId, style, base, entry, auth = credentials.auth(profile, KeyHeader.BEARER))
    }

    private suspend fun mantle(profile: Profile, entry: ModelEntry): ModelTarget {
        val region = profile.region ?: throw ProviderException(ProviderError.NotAllowed)
        val route = try {
            MantleRouting.route(entry.id, region)
        } catch (e: IllegalArgumentException) {
            // A region that is not one of AWS's names never becomes part of a host.
            throw ProviderException(ProviderError.NotAllowed)
        }
        // The bearer is written in overrides.json, not hidden in code (design D4).
        val header = catalog.choice(profile.providerId, profile.choiceId)?.settings?.keyHeader ?: KeyHeader.BEARER
        return ModelTarget(profile.providerId, route.style, route.baseUrl, entry, auth = credentials.auth(profile, header))
    }

    private suspend fun vertex(profile: Profile, entry: ModelEntry): ModelTarget {
        val key = profile.credentialId?.let { credentials.read(it) } as? Credential.ServiceAccountKey
            ?: throw ProviderException(ProviderError.SignInExpired(profile.providerId))
        val location = profile.region?.takeIf(VertexUrls::validLocation) ?: throw ProviderException(ProviderError.NotAllowed)
        // VertexUrls.base refuses a project ID that is not in Google's form (plan 4B), because it becomes part of the path.
        val project = key.projectId.takeIf(VertexUrls::validProject) ?: throw ProviderException(ProviderError.NotAllowed)
        return ModelTarget(
            providerId = profile.providerId,
            style = RequestStyle.VERTEX,
            baseUrl = VertexUrls.base(location, project),
            model = entry,
            auth = credentials.auth(profile, KeyHeader.BEARER),
            // The Vertex adapter takes the row's wire, and else decides by the model ID (plan 4B).
            options = StyleOptions(wire = entry.style),
        )
    }

    private suspend fun azure(profile: Profile, chosen: ProfileModel, entry: ModelEntry): ModelTarget {
        val endpoint = profile.baseUrl?.let(AzureRouting::normalise) ?: throw ProviderException(ProviderError.NotAllowed)
        // The same wire as AzureAdapter's: the owner's choice, else a deployment whose name starts with "claude" is Claude.
        val wire = chosen.style ?: AzureRouting.styleFor(entry.id)
        val header = if (wire == RequestStyle.ANTHROPIC) KeyHeader.X_API_KEY else KeyHeader.API_KEY
        return ModelTarget(
            providerId = profile.providerId,
            style = RequestStyle.AZURE,
            baseUrl = endpoint,
            model = entry,
            auth = credentials.auth(profile, header),
            options = StyleOptions(wire = wire),
        )
    }

    private fun fixedHeaders(headers: Map<String, String>): RequestHeaders =
        if (headers.isEmpty()) RequestHeaders.NONE else RequestHeaders { headers }

    companion object {
        const val MANTLE = "amazon-bedrock-mantle"
        const val GROK_PROXY = "cli-chat-proxy.grok.com"

        /** The key header of each style when the choice names none (design D4). */
        fun defaultKeyHeader(style: RequestStyle): KeyHeader = when (style) {
            RequestStyle.ANTHROPIC -> KeyHeader.X_API_KEY
            RequestStyle.GEMINI -> KeyHeader.X_GOOG_API_KEY
            RequestStyle.AZURE -> KeyHeader.API_KEY
            RequestStyle.CHAT_COMPLETIONS, RequestStyle.RESPONSES, RequestStyle.OLLAMA, RequestStyle.BEDROCK, RequestStyle.VERTEX -> KeyHeader.BEARER
        }
    }
}

/** A profile's model as the catalog row that the adapters read (design D7 and D13). */
object ModelEntries {
    /**
     * The catalog's row of [modelId], with its price, capabilities and compat, and the style and the lack of tool support
     * that the provider's list reports (Copilot's per-model style); else the row that the list gives, with the input kinds,
     * reasoning, efforts, limits and tool support that it reports; else a row with only the ID, text input and the style's
     * defaults.
     */
    fun entryFor(provider: ProviderEntry?, modelId: String, discovered: List<DiscoveredModel>): ModelEntry {
        val row = provider?.models?.firstOrNull { it.id == modelId }
        val found = discovered.firstOrNull { it.id == modelId }
        return when {
            row != null -> row.copy(style = found?.style ?: row.style, toolMode = if (found?.tools == false) ToolMode.JSON else row.toolMode)
            found != null -> fromDiscovered(found)
            else -> ModelEntry(modelId, modelId)
        }
    }

    /** A row from a model that only the provider's list knows; a model that reports no tool support gets JSON actions. */
    fun fromDiscovered(found: DiscoveredModel): ModelEntry =
        ModelEntry(
            id = found.id,
            name = found.name,
            style = found.style,
            input = found.input,
            reasoning = found.reasoning,
            contextWindow = found.contextWindow,
            maxTokens = found.maxTokens,
            compat = CompatFlags(supportsTools = found.tools, supportedReasoningEfforts = found.reasoningEfforts),
            toolMode = if (found.tools == false) ToolMode.JSON else ToolMode.NATIVE,
        )

    /** The owner's switches on the profile page: "Use JSON actions", and a local server's "Accepts images" and "Handles tool calls". */
    fun withOwnerChoices(entry: ModelEntry, chosen: ProfileModel): ModelEntry {
        val input = when (chosen.images) {
            true -> entry.input + InputKind.IMAGE
            false -> entry.input - InputKind.IMAGE
            null -> entry.input
        }
        val json = chosen.jsonActions || chosen.tools == false || (chosen.tools == null && entry.toolMode == ToolMode.JSON)
        return entry.copy(input = input, toolMode = if (json) ToolMode.JSON else ToolMode.NATIVE)
    }
}

/** The default models of a new profile: model IDs, which for a local server may be the owner's own names. */
data class ModelDefaults(val lightweight: String?, val versatile: String?) {
    override fun toString(): String = "ModelDefaults(lightweight=${lightweight != null}, versatile=${versatile != null})"
}

/** The models that a profile offers for both roles, and the defaults among them (design D12 and D13). */
object OfferedModels {
    private val LISTED_ONLY = setOf(SignInKind.GITHUB, SignInKind.GITHUB_TENANT, SignInKind.CHATGPT_PLAN, SignInKind.XAI)

    /**
     * The rows that a profile of [providerId] with [choiceId] offers: for the sign-ins whose list is the account's own
     * (Copilot, the ChatGPT plan and xAI's sign-in) only the listed rows, each with the catalog's price, capabilities and
     * compat where the catalog has it; for every other choice the catalog's rows (with those that `overrides.json` adds,
     * such as Mantle's Claude rows) beside the listed ones. A row without tool support carries JSON actions.
     */
    fun rows(catalog: Catalog, providerId: String, choiceId: String, discovered: List<DiscoveredModel>): List<ModelEntry> {
        val provider = catalog.provider(providerId)
        val listed = discovered.map { ModelEntries.entryFor(provider, it.id, discovered) }
        if (SignInRegistry.kind(providerId, choiceId) in LISTED_ONLY) return listed
        return provider?.models.orEmpty().filter { row -> listed.none { it.id == row.id } } + listed
    }

    /**
     * The defaults among [rows]: for Copilot the first of its lightweight and of its versatile names that the rows offer
     * with tool calls, by ID or normalised name; for the others the catalog's utility model and default model when the
     * rows have them; else the first row for both.
     */
    fun defaults(catalog: Catalog, providerId: String, rows: List<ModelEntry>): ModelDefaults {
        val first = rows.firstOrNull()?.id ?: return ModelDefaults(null, null)
        val provider = catalog.provider(providerId)
        val copilot = catalog.overrides.copilot
        val light = if (providerId == CatalogLoader.COPILOT) byName(copilot.lightweight, rows) else rows.firstOrNull { it.id == provider?.utilityModel }?.id
        val versatile = if (providerId == CatalogLoader.COPILOT) byName(copilot.versatile, rows) else rows.firstOrNull { it.id == provider?.defaultModel }?.id
        return ModelDefaults(light ?: first, versatile ?: first)
    }

    private fun byName(names: List<String>, rows: List<ModelEntry>): String? =
        names.firstNotNullOfOrNull { name ->
            val wanted = CatalogLoader.normalise(name)
            rows.firstOrNull { it.toolMode == ToolMode.NATIVE && (CatalogLoader.normalise(it.name) == wanted || CatalogLoader.normalise(it.id) == wanted) }?.id
        }
}
