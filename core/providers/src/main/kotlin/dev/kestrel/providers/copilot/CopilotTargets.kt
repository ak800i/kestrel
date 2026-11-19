package dev.kestrel.providers.copilot

import dev.kestrel.providers.catalog.Catalog
import dev.kestrel.providers.catalog.CatalogLoader
import dev.kestrel.providers.catalog.CompatFlags
import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.chat.ModelTarget
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.chat.StyleOptions
import dev.kestrel.providers.client.ProviderTargets
import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.credentials.CredentialManager
import dev.kestrel.providers.http.KeyHeader
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.profiles.ModelRole
import dev.kestrel.providers.profiles.Profile
import dev.kestrel.providers.signin.SignInFailed
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Copilot's targets (design D12): each model's style from its `supported_endpoints`, the base that [discovery] finds
 * before the first Copilot request in each process, the runtime headers with `x-initiator` and the vision header, and the
 * GitHub token as the bearer. Anthropic Messages goes to `<base>/messages` with `stripThinking` (no thinking blocks for
 * any Claude model, as OpenClaw's Copilot replay policy does) and with sanitised
 * tool-call IDs; Chat Completions uses Copilot's compat flags; every Copilot request streams. When the base differs from
 * the profile's stored one, [updateBase] stores it and waits until the allowlist holds its host; when discovery fails
 * for lack of network, the stored base is used for this process. The base is kept per profile for the process, so that a
 * client that sub-project 5 keeps uses the new base too. Requests of one profile that start together run discovery once:
 * the later ones wait for the first one's base.
 */
class CopilotTargets(
    private val catalog: Catalog,
    private val credentials: CredentialManager,
    private val discovery: CopilotDiscovery,
    private val updateBase: suspend (Profile, HttpUrl) -> Unit,
) : ProviderTargets {
    private val bases = ConcurrentHashMap<String, HttpUrl>()
    private val discovering = ConcurrentHashMap<String, Mutex>()

    /** Forgets [profileId]'s base, as after "Sign in again", which may be another GitHub account. */
    fun forget(profileId: String) {
        bases.remove(profileId)
    }

    override suspend fun target(profile: Profile, role: ModelRole, entry: ModelEntry): ModelTarget {
        val base = base(profile)
        val style = profile.model(role).style ?: entry.style ?: CopilotModels.styleFor(entry.id, null, emptyList())
        return ModelTarget(
            providerId = CatalogLoader.COPILOT,
            style = style,
            baseUrl = base,
            model = entry,
            auth = credentials.auth(profile, KeyHeader.BEARER),
            headers = CopilotHeaders.forRequests(catalog.overrides.copilot.runtimeHeaders),
            options = options(style, entry.compat),
        )
    }

    private suspend fun base(profile: Profile): HttpUrl {
        bases[profile.id]?.let { return it }
        return discovering.computeIfAbsent(profile.id) { Mutex() }.withLock { bases[profile.id] ?: discover(profile) }
    }

    private suspend fun discover(profile: Profile): HttpUrl {
        val stored = profile.baseUrl?.toHttpUrlOrNull()
        val token = profile.credentialId?.let { credentials.read(it) } as? Credential.GitHubToken
            ?: throw ProviderException(ProviderError.SignInExpired(CatalogLoader.COPILOT))
        val found = try {
            discovery.apiBase(token)
        } catch (e: SignInFailed) {
            // Copilot named an address that Kestrel does not trust (design D12): the request fails, and no token goes there.
            throw ProviderException(ProviderError.Malformed)
        } catch (e: ProviderException) {
            if (stored != null && (e.error == ProviderError.Unreachable || e.error == ProviderError.Timeout)) {
                bases[profile.id] = stored
                return stored
            }
            throw e
        }
        if (found != stored) updateBase(profile, found)
        bases[profile.id] = found
        return found
    }

    companion object {
        /** Copilot's Chat Completions flags (research r27). */
        val COMPAT = CompatFlags(supportsStore = false, supportsDeveloperRole = false, supportsUsageInStreaming = false, maxTokensField = "max_tokens")

        /**
         * The options of a Copilot request in [style]. On Chat Completions, Copilot's flags replace those of the model's
         * [compat], which keeps the rest, such as its reasoning efforts.
         */
        fun options(style: RequestStyle, compat: CompatFlags = CompatFlags()): StyleOptions = when (style) {
            RequestStyle.ANTHROPIC -> StyleOptions(stripThinking = true, sanitizeToolCallIds = true, messagesPath = "/messages", stream = true)
            RequestStyle.CHAT_COMPLETIONS -> StyleOptions(
                compat = compat.copy(
                    supportsStore = COMPAT.supportsStore,
                    supportsDeveloperRole = COMPAT.supportsDeveloperRole,
                    supportsUsageInStreaming = COMPAT.supportsUsageInStreaming,
                    maxTokensField = COMPAT.maxTokensField,
                ),
                stream = true,
            )
            else -> StyleOptions(stream = true)
        }
    }
}
