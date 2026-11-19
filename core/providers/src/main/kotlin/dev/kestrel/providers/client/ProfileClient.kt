package dev.kestrel.providers.client

import dev.kestrel.providers.catalog.Catalog
import dev.kestrel.providers.catalog.ToolMode
import dev.kestrel.providers.chat.ChatAdapter
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.ChatResponse
import dev.kestrel.providers.chat.ModelTarget
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.cost.Cost
import dev.kestrel.providers.cost.CostMeter
import dev.kestrel.providers.cost.PriceTable
import dev.kestrel.providers.cost.Spending
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.profiles.ModelRole
import dev.kestrel.providers.profiles.Profile
import dev.kestrel.providers.profiles.ProvidersSetting
import dev.kestrel.providers.styles.JsonActions
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneOffset

/** A provider's answer with its cost and the style that carried it (design D14). toString prints no reply, only kinds and counts. */
data class PricedResponse(val response: ChatResponse, val cost: Cost, val style: RequestStyle) {
    override fun toString(): String =
        "PricedResponse(stop=${response.stop}, parts=${response.message.parts.size}, usage=${response.usage}, " +
            "modelIdLength=${response.modelId.length}, invalidToolCall=${response.invalidToolCall?.kind}, cost=$cost, style=$style)"
}

/** Where ProfileClient reports that a plan's usage limit was reached, and that a later call worked (design D17). */
interface PlanLimits {
    fun reached(profileId: String)

    fun cleared(profileId: String)
}

/**
 * What sub-project 5 calls for one profile (design D4, D11, D13 and D14). [complete] waits at most 5 s until the network
 * allowlist holds the profile ([applied], which `:app`'s allowlist job publishes after it added the setting's hosts),
 * refuses a profile whose borrowed sign-in is switched off and, before each attempt, a server on the owner's network
 * without Android's permission, resolves the role's model with [targets], runs the adapter (inside JsonActions for a
 * model with JSON actions) with a limit of 30 s per attempt, and retries a timeout, a rate limit, a server error or an
 * unreachable provider twice, after 1 s and 3 s or after a `Retry-After` of at most 10 s. A longer `Retry-After` fails at
 * once, so that sub-project 5 can switch models; a QuotaOrPolicy is never retried, and on a ChatGPT plan profile its 429
 * marks the plan's usage limit until a later call works. Each answer is priced, and its credits and tokens are added to
 * the month's spending, also when the caller stops meanwhile. A stop reaches the caller as a CancellationException,
 * unchanged. Each call reads the profile with [profile]'s ID from the applied setting, for its hosts, its target and its
 * price, so that a client that sub-project 5 keeps follows the owner's edits, a new Copilot base and a new sign-in;
 * resolving the target has the limit of one attempt.
 */
class ProfileClient(
    val profile: Profile,
    private val catalog: Catalog,
    private val applied: StateFlow<ProvidersSetting?>,
    private val targets: suspend (Profile, ModelRole) -> ModelTarget,
    private val adapters: (RequestStyle) -> ChatAdapter,
    private val localNetwork: LocalNetworkAccess,
    private val spend: suspend (profileId: String, month: String, credits: Double, tokens: Long) -> Unit,
    private val limits: PlanLimits,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val prices = PriceTable(catalog)

    suspend fun complete(role: ModelRole, request: ChatRequest): PricedResponse {
        // The applied setting's profiles are those whose hosts the allowlist holds, so the call uses the profile as the
        // applied setting holds it, also after the owner changed its address or the discovery its hosts (design D13).
        val setting = withTimeoutOrNull(ALLOWLIST_WAIT_MILLIS) {
            applied.first { applied -> applied?.profiles?.any { it.id == profile.id } == true }
        } ?: throw ProviderException(ProviderError.NotAllowed)
        val current = setting.profiles.first { it.id == profile.id }
        if (!setting.usable(current, catalog)) throw ProviderException(ProviderError.SignInSwitchedOff(profile.providerId))
        // Copilot's discovery is a network call, so the target has the limit of one attempt too (design D4).
        val target = withTimeoutOrNull(ATTEMPT_MILLIS) { targets(current, role) } ?: throw ProviderException(ProviderError.Timeout)
        val host = target.baseUrl.host
        val adapter = adapters(target.style).let { if (target.model.toolMode == ToolMode.JSON) JsonActions(it) else it }
        val plan = catalog.choice(current.providerId, current.choiceId)?.settings?.chatgptPlan == true
        var attempt = 0
        while (true) {
            // Before each attempt, so that a permission that the owner took back stops the retries too (design D11).
            if (LocalNetwork.needsPermission(host) && !localNetwork.allowed(host)) throw ProviderException(ProviderError.LocalNetworkNotAllowed)
            val failure = try {
                val response = withTimeoutOrNull(ATTEMPT_MILLIS) { adapter.complete(request, target) }
                if (response != null) {
                    limits.cleared(profile.id)
                    return priced(current, response, target)
                }
                ProviderError.Timeout
            } catch (e: ProviderException) {
                val error = e.error
                if (plan && error is ProviderError.QuotaOrPolicy && error.status == 429) limits.reached(profile.id)
                error
            }
            val wait = retryWait(failure, attempt) ?: throw ProviderException(failure)
            attempt++
            pause(wait)
        }
    }

    private suspend fun priced(current: Profile, response: ChatResponse, target: ModelTarget): PricedResponse {
        val now = clock()
        val day = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate()
        val choice = prices.priceFor(current, target.model.id, target.model.name, day)
        val cost = CostMeter.cost(response.usage, choice)
        // The provider has answered and charges for it, so a stop of the caller at this moment still counts it.
        withContext(NonCancellable) {
            spend(current.id, Spending.monthOf(now), cost.credits, response.usage.totalInput + response.usage.output)
        }
        return PricedResponse(response, cost, target.style)
    }

    /** The pause before the next attempt, or null when [error] ends the call (design D4). */
    private fun retryWait(error: ProviderError, attempt: Int): Long? {
        if (attempt >= RETRY_PAUSES.size) return null
        return when (error) {
            ProviderError.Timeout, ProviderError.Unreachable, is ProviderError.ServerError -> RETRY_PAUSES[attempt]
            is ProviderError.RateLimited -> error.retryAfterMillis?.let { after -> after.takeIf { it <= MAX_RETRY_AFTER_MILLIS } ?: return null }
                ?: RETRY_PAUSES[attempt]
            else -> null
        }
    }

    override fun toString(): String = "ProfileClient(profile=$profile)"

    private companion object {
        const val ALLOWLIST_WAIT_MILLIS = 5_000L
        const val ATTEMPT_MILLIS = 30_000L
        const val MAX_RETRY_AFTER_MILLIS = 10_000L
        val RETRY_PAUSES = listOf(1_000L, 3_000L)
    }
}
