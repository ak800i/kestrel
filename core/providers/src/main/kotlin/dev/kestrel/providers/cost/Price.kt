package dev.kestrel.providers.cost

import kotlinx.serialization.Serializable

/**
 * A price in US dollars per million tokens (design D14). A missing cache price counts those tokens at the input price.
 * [tiers] apply when the request's whole input is above their threshold; [validFrom] and [validUntil] are UTC days
 * (`yyyy-MM-dd`), and [validUntil] is exclusive.
 */
@Serializable
data class Price(
    val input: Double,
    val output: Double,
    val cacheRead: Double? = null,
    val cacheWrite: Double? = null,
    val tiers: List<PriceTier> = emptyList(),
    val validFrom: String? = null,
    val validUntil: String? = null,
) {
    companion object {
        /** A local server on the owner's own network, or a model that overrides.json lists as free. */
        val FREE = Price(input = 0.0, output = 0.0, cacheRead = 0.0, cacheWrite = 0.0)
    }
}

/** A long-context price that applies above [aboveInputTokens] input tokens (cache reads and writes included). */
@Serializable
data class PriceTier(
    val aboveInputTokens: Long,
    val input: Double,
    val output: Double,
    val cacheRead: Double? = null,
    val cacheWrite: Double? = null,
)
