package dev.kestrel.app.agent

import dev.kestrel.agent.port.ModelInfo
import dev.kestrel.agent.port.ModelPort
import dev.kestrel.agent.port.RoleModel
import dev.kestrel.providers.catalog.CatalogLoader
import dev.kestrel.providers.catalog.InputKind
import dev.kestrel.providers.catalog.LocalServerKind
import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.catalog.ProviderEntry
import dev.kestrel.providers.chat.CacheFields
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.chat.Usage
import dev.kestrel.providers.client.ModelEntries
import dev.kestrel.providers.client.PricedResponse
import dev.kestrel.providers.discovery.DiscoveredModel
import dev.kestrel.providers.profiles.ModelRole
import dev.kestrel.providers.profiles.Profile
import java.util.concurrent.ConcurrentHashMap

/**
 * The default profile's two models for one task (design D1, D5 and D7 of sub-project 5): [complete] is the profile's
 * `ProfileClient.complete`. Each call carries the cache fields that [cache] gives for the role's model, and its answer
 * tells [cache] whether the explicit Responses mode worked.
 */
internal class AppModelPort(
    override val model: ModelInfo,
    private val cache: CacheState,
    private val complete: suspend (ModelRole, ChatRequest) -> PricedResponse,
) : ModelPort {
    override suspend fun call(role: ModelRole, request: ChatRequest, step: Int): PricedResponse {
        val id = (if (role == ModelRole.LIGHTWEIGHT) model.lightweight else model.versatile).id
        val fields = cache.fields(id)
        val answer = complete(role, request.copy(cacheFields = fields))
        cache.answered(id, step, fields, answer.response.usage)
        return answer
    }

    override fun toString(): String = "AppModelPort(provider=${model.providerId})"
}

/**
 * Which of Copilot's cache fields the requests carry (design D5 of sub-project 5): the stored setting `AGENT_CACHE_FIELDS`
 * ([stored], which only the debug hook `agent-cache` changes), and, for the rest of the process, without the explicit
 * Responses mode for a model whose request of step 3 or later, above 1,024 input tokens, read nothing from the cache:
 * dropped markers disable caching without an error (r51). One state serves every task.
 */
internal class CacheState(private val stored: suspend () -> CacheFields) {
    private val withoutExplicit: MutableSet<String> = ConcurrentHashMap.newKeySet()

    suspend fun fields(modelId: String): CacheFields {
        val fields = stored()
        return if (modelId in withoutExplicit) fields.copy(responsesExplicit = false) else fields
    }

    fun answered(modelId: String, step: Int, fields: CacheFields, usage: Usage) {
        if (fields.responsesExplicit && step >= 3 && usage.totalInput > MIN_INPUT && usage.cacheRead == 0L) withoutExplicit += modelId
    }

    override fun toString(): String = "CacheState(withoutExplicit=${withoutExplicit.size})"

    companion object {
        const val MIN_INPUT = 1_024L
    }
}

/**
 * The profile checks before a task (design D32 items 36 and 37 of sub-project 5; the controller's decision 4 for plan
 * 5B): a role whose model cannot work gets the sentence that says why, which the agent speaks when it is the lightweight
 * model and which keeps it from escalating when it is the versatile one.
 */
internal object ProfileChecks {
    const val VERTEX_LOCATION = "Claude Opus 5 and Sonnet 5 work on Vertex only in the global, us and eu locations. Change the profile's location or model."
    const val BEDROCK_PROFILE = "Kestrel can't use Bedrock application inference profiles yet. Choose the model by its own ID."
    const val OLLAMA_WINDOW = "This model's context is too small for tasks: Kestrel needs at least 12,000 tokens. Choose a larger model, or raise the server's context length."

    /** The system prompt and the tools take about 4,000 tokens, and the agent asks for 4,096 output tokens. */
    const val MIN_OLLAMA_WINDOW = 12_000

    private val VERTEX_LOCATIONS = setOf("global", "us", "eu")
    private val CLAUDE_5 = Regex("^claude-(opus|sonnet)-5(@[\\w.-]+|-\\d{8})?$", RegexOption.IGNORE_CASE)
    private const val APPLICATION_PROFILE = ":application-inference-profile/"

    fun unusable(profile: Profile, style: RequestStyle?, entry: ModelEntry): String? = when {
        style == RequestStyle.VERTEX && profile.region !in VERTEX_LOCATIONS && CLAUDE_5.matches(entry.id) -> VERTEX_LOCATION
        style == RequestStyle.BEDROCK && APPLICATION_PROFILE in entry.id -> BEDROCK_PROFILE
        profile.localServer == LocalServerKind.OLLAMA && (entry.contextWindow ?: Int.MAX_VALUE) < MIN_OLLAMA_WINDOW -> OLLAMA_WINDOW
        else -> null
    }
}

/** What the agent knows of a profile (design D1 and D7 of sub-project 5): no key and no token. */
internal object ModelInfos {
    // The Claude models that keep their signed thinking (D32 item 9): Opus, Sonnet and Haiku from 5.5 on, Fable and Mythos from 5.1 on.
    private val SIGNED_THINKING = Regex("(^|[-/.])claude-((opus|sonnet|haiku)-(5-[5-9]|[6-9])|(fable|mythos)-(5-[1-9]|[6-9]))(?=$|[^a-z0-9])")

    /**
     * The profile's two models, as the catalog row of [provider] and the profile's last model list ([discovered]) describe
     * them; [lightweight] replaces the lightweight model's ID for this task only (design D28).
     */
    fun of(profile: Profile, provider: ProviderEntry?, discovered: List<DiscoveredModel>, lightweight: String? = null): ModelInfo {
        val copilot = profile.providerId == CatalogLoader.COPILOT
        fun role(role: ModelRole): RoleModel {
            val chosen = profile.model(role).let { if (role == ModelRole.LIGHTWEIGHT && lightweight != null) it.copy(id = lightweight) else it }
            val entry = ModelEntries.withOwnerChoices(ModelEntries.entryFor(provider, chosen.id, discovered), chosen)
            val style = chosen.style ?: entry.style ?: provider?.style
                ?: if (profile.localServer == LocalServerKind.OLLAMA) RequestStyle.OLLAMA else RequestStyle.CHAT_COMPLETIONS
            return RoleModel(
                name = entry.name,
                id = entry.id,
                style = style,
                contextWindow = entry.contextWindow,
                takesImages = InputKind.IMAGE in entry.input,
                signedThinking = !copilot && SIGNED_THINKING.containsMatchIn(entry.id.lowercase().replace(Regex("[._\\s]+"), "-")),
                unusable = ProfileChecks.unusable(profile, provider?.style, entry),
            )
        }
        return ModelInfo(
            providerName = provider?.label ?: profile.providerId,
            providerId = profile.providerId,
            countsCredits = copilot,
            localOllama = profile.localServer == LocalServerKind.OLLAMA,
            lightweight = role(ModelRole.LIGHTWEIGHT),
            versatile = role(ModelRole.VERSATILE),
        )
    }
}
