package dev.kestrel.providers.cost

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

/** The credits and the tokens of one profile in one month (design D14). */
@Serializable
data class MonthUse(val credits: Double = 0.0, val tokens: Long = 0)

/**
 * The spending of each profile per calendar month in UTC (month `yyyy-MM` → profile ID → use), for the current month and
 * the one before, because Copilot's allowance resets at 00:00 UTC on the 1st (research r28). Kept in one encrypted setting.
 */
@Serializable
data class Spending(val months: Map<String, Map<String, MonthUse>> = emptyMap()) {
    /**
     * Adds one call and keeps [month], the month before it and any later month, so that a call that is counted late never
     * drops a newer month.
     */
    fun add(profileId: String, month: String, credits: Double, tokens: Long): Spending {
        val before = YearMonth.parse(month).minusMonths(1).toString()
        val current = months[month].orEmpty()
        val use = current[profileId] ?: MonthUse()
        val updated = months + (month to current + (profileId to MonthUse(use.credits + credits, use.tokens + tokens)))
        return Spending(updated.filterKeys { it >= before })
    }

    fun of(profileId: String, month: String): MonthUse = months[month]?.get(profileId) ?: MonthUse()

    companion object {
        /** The UTC month of [epochMillis], as `yyyy-MM`. */
        fun monthOf(epochMillis: Long): String = YearMonth.from(Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC)).toString()
    }
}
