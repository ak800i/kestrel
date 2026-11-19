package dev.kestrel.providers.cost

import dev.kestrel.providers.chat.Usage
import org.junit.Assert.assertEquals
import org.junit.Test

class CostMeterTest {
    private val luna = Price(
        input = 0.10, output = 0.50, cacheRead = 0.01, cacheWrite = 0.125,
        tiers = listOf(PriceTier(aboveInputTokens = 272_000, input = 0.20, output = 0.75, cacheRead = 0.02, cacheWrite = 0.25)),
    )

    @Test
    fun eachBucketHasItsPriceAndCreditsAreCents() {
        val cost = CostMeter.cost(Usage(input = 100_000, output = 2_000, cacheRead = 50_000, cacheWrite = 10_000), PriceChoice(luna, known = true))

        // 100,000 × 0.10 + 2,000 × 0.50 + 50,000 × 0.01 + 10,000 × 0.125 = 12,750 per million, so $0.01275.
        assertEquals(0.01275, cost.usd, 1e-12)
        assertEquals(1.275, cost.credits, 1e-9)
        assertEquals(Cost(cost.usd, cost.credits, estimated = false, priceKnown = true, planUsage = false), cost)
    }

    @Test
    fun theTierAppliesWhenTheWholeInputIsAboveItsThreshold() {
        val below = CostMeter.cost(Usage(input = 200_000, output = 0, cacheRead = 72_000), PriceChoice(luna, known = true))
        val above = CostMeter.cost(Usage(input = 200_000, output = 0, cacheRead = 72_001), PriceChoice(luna, known = true))

        assertEquals((200_000 * 0.10 + 72_000 * 0.01) / 1e6, below.usd, 1e-12)
        assertEquals((200_000 * 0.20 + 72_001 * 0.02) / 1e6, above.usd, 1e-12)
    }

    @Test
    fun aMissingCachePriceCountsAtTheInputPrice() {
        val gemini = Price(input = 1.5, output = 7.5, cacheRead = 0.075)

        val cost = CostMeter.cost(Usage(input = 0, output = 0, cacheRead = 1_000_000, cacheWrite = 1_000_000), PriceChoice(gemini, known = true))

        assertEquals(0.075 + 1.5, cost.usd, 1e-12)
    }

    @Test
    fun theUnknownPriceReachesTenCreditsWithTwentyThousandInputTokens() {
        val cost = CostMeter.cost(Usage(input = 20_000, output = 0, estimated = true), PriceChoice(Price(5.0, 25.0, 0.5, 6.25), known = false))

        assertEquals(10.0, cost.credits, 1e-9)
        assertEquals(false, cost.priceKnown)
        assertEquals(true, cost.estimated)
    }

    @Test
    fun aPlanPaysSoTheCallCostsNoCredits() {
        val cost = CostMeter.cost(Usage(input = 1_000_000, output = 1_000_000), PriceChoice(Price.FREE, known = true, planUsage = true))

        assertEquals(Cost(0.0, 0.0, estimated = false, priceKnown = true, planUsage = true), cost)
    }
}
