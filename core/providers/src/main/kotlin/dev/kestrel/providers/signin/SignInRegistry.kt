package dev.kestrel.providers.signin

import dev.kestrel.providers.http.ProviderHttp

/** The sign-in flows that are tied to one choice (design D9). */
enum class SignInKind { GITHUB, GITHUB_TENANT, XAI, MINIMAX_GLOBAL, MINIMAX_CHINA, CHATGPT_PLAN, OPENROUTER }

/**
 * The flows and refreshers keyed by provider and choice ID (design D9). The IDs are those of the catalog that plan 4A
 * synced; SignInRegistryTest checks that each key exists in it with the method of its flow.
 */
object SignInRegistry {
    val flows: Map<Pair<String, String>, SignInKind> = mapOf(
        ("github-copilot" to "github-copilot") to SignInKind.GITHUB,
        ("github-copilot" to "github-copilot-enterprise") to SignInKind.GITHUB_TENANT,
        ("xai" to "xai-oauth") to SignInKind.XAI,
        ("minimax-portal" to "minimax-global-oauth") to SignInKind.MINIMAX_GLOBAL,
        ("minimax-portal" to "minimax-cn-oauth") to SignInKind.MINIMAX_CHINA,
        ("openai" to "chatgpt-plan") to SignInKind.CHATGPT_PLAN,
        ("openrouter" to "openrouter-oauth") to SignInKind.OPENROUTER,
    )

    fun kind(providerId: String, choiceId: String): SignInKind? = flows[providerId to choiceId]

    /** The refresher of the choice's OAuth tokens, or null for a credential that cannot be refreshed. */
    fun refresher(providerId: String, choiceId: String, http: ProviderHttp, clock: () -> Long = System::currentTimeMillis): TokenRefresher? =
        when (kind(providerId, choiceId)) {
            SignInKind.CHATGPT_PLAN -> ChatGptPlanRefresher(http, clock)
            SignInKind.XAI -> XaiRefresher(http, clock)
            SignInKind.MINIMAX_GLOBAL -> MiniMaxRefresher(http, clock, MiniMaxRegion.GLOBAL)
            SignInKind.MINIMAX_CHINA -> MiniMaxRefresher(http, clock, MiniMaxRegion.CHINA)
            SignInKind.GITHUB, SignInKind.GITHUB_TENANT, SignInKind.OPENROUTER, null -> null
        }
}
