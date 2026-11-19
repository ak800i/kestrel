package dev.kestrel.providers.cost

import dev.kestrel.providers.catalog.Catalog
import dev.kestrel.providers.catalog.CatalogLoader
import dev.kestrel.providers.http.Hosts
import dev.kestrel.providers.profiles.Profile
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.LocalDate

/**
 * The price that a call is counted at, whether Kestrel knows it, and whether the owner's plan pays for the call instead
 * (design D14).
 */
data class PriceChoice(val price: Price, val known: Boolean, val planUsage: Boolean = false)

/**
 * Chooses the price of a model for a profile (design D14), in this order: a plan sign-in (no per-token price); the price
 * that the owner typed; the catalog's price for the day (Copilot's from `overrides.copilot` by ID or normalised name);
 * free for a local server at a local address and for the models that overrides.json lists as free; otherwise the unknown
 * price, which errs on the safe side.
 */
class PriceTable(private val catalog: Catalog) {
    fun priceFor(profile: Profile, modelId: String, modelName: String?, day: LocalDate): PriceChoice {
        val choice = catalog.choice(profile.providerId, profile.choiceId)?.settings
        if (choice?.planUsage == true) return PriceChoice(Price.FREE, known = true, planUsage = true)
        listOf(profile.lightweight, profile.versatile).firstOrNull { it.id == modelId && it.price != null }?.let {
            return PriceChoice(it.price!!, known = true)
        }
        val prices = if (profile.providerId == CatalogLoader.COPILOT) {
            CatalogLoader.copilotPrice(catalog.overrides.copilot, modelId, modelName)?.prices
        } else {
            catalog.provider(profile.providerId)?.models?.firstOrNull { it.id == modelId }?.prices
        }
        on(prices.orEmpty(), day)?.let { return PriceChoice(it, known = true) }
        val localHost = profile.localServer != null && profile.baseUrl?.toHttpUrlOrNull()?.host?.let(Hosts::isLocal) == true
        val free = catalog.overrides.freeModels.any { it.provider == profile.providerId && it.model == modelId }
        if (localHost || free) return PriceChoice(Price.FREE, known = true)
        return PriceChoice(catalog.overrides.unknownPrice, known = false)
    }

    /** The price that holds on [day] (UTC): from its `validFrom` on, and before its `validUntil`. */
    private fun on(prices: List<Price>, day: LocalDate): Price? =
        prices.firstOrNull { price ->
            (price.validFrom == null || !day.isBefore(LocalDate.parse(price.validFrom))) &&
                (price.validUntil == null || day.isBefore(LocalDate.parse(price.validUntil)))
        }
}
