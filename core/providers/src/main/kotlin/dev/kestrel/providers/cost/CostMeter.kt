package dev.kestrel.providers.cost

import dev.kestrel.providers.chat.Usage

/**
 * The cost of one call (design D14): [credits] are GitHub AI Credits, where 1 credit is $0.01 (B28). A call that a plan
 * pays for costs 0 credits and has [planUsage]; [estimated] says that the provider reported no usage.
 */
data class Cost(val usd: Double, val credits: Double, val estimated: Boolean, val priceKnown: Boolean, val planUsage: Boolean)

object CostMeter {
    /**
     * Prices [usage] at [choice]: the long-context tier applies when the whole input (cache reads and writes included) is
     * above its threshold, a missing cache price counts those tokens at the input price, and reasoning is part of output.
     */
    fun cost(usage: Usage, choice: PriceChoice): Cost {
        if (choice.planUsage) return Cost(0.0, 0.0, usage.estimated, priceKnown = true, planUsage = true)
        val price = choice.price
        val tier = price.tiers.filter { usage.totalInput > it.aboveInputTokens }.maxByOrNull { it.aboveInputTokens }
        val input = tier?.input ?: price.input
        val output = tier?.output ?: price.output
        val cacheRead = (if (tier != null) tier.cacheRead else price.cacheRead) ?: input
        val cacheWrite = (if (tier != null) tier.cacheWrite else price.cacheWrite) ?: input
        val usd = (usage.input * input + usage.output * output + usage.cacheRead * cacheRead + usage.cacheWrite * cacheWrite) / 1_000_000.0
        return Cost(usd, credits = usd * 100, estimated = usage.estimated, priceKnown = choice.known, planUsage = false)
    }
}
