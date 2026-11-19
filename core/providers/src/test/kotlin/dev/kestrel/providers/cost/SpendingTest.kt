package dev.kestrel.providers.cost

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class SpendingTest {
    @Test
    fun callsAddUpPerProfileAndMonth() {
        val spending = Spending()
            .add("p1", "2026-11", credits = 1.5, tokens = 1_000)
            .add("p1", "2026-11", credits = 0.5, tokens = 500)
            .add("p2", "2026-11", credits = 2.0, tokens = 10)

        assertEquals(MonthUse(2.0, 1_500), spending.of("p1", "2026-11"))
        assertEquals(MonthUse(2.0, 10), spending.of("p2", "2026-11"))
        assertEquals(MonthUse(), spending.of("p3", "2026-11"))
    }

    @Test
    fun onlyThisMonthAndTheOneBeforeAreKept() {
        val spending = Spending()
            .add("p1", "2026-10", 1.0, 1)
            .add("p1", "2026-11", 1.0, 1)
            .add("p1", "2026-12", 1.0, 1)
            .add("p1", "2027-01", 1.0, 1)

        assertEquals(setOf("2026-12", "2027-01"), spending.months.keys)
        assertEquals(spending, Json.decodeFromString(Spending.serializer(), Json.encodeToString(Spending.serializer(), spending)))
    }

    @Test
    fun theMonthIsTheUtcMonth() {
        assertEquals("2026-11", Spending.monthOf(Instant.parse("2026-11-30T23:59:59Z").toEpochMilli()))
        assertEquals("2026-12", Spending.monthOf(Instant.parse("2026-12-01T00:00:00Z").toEpochMilli()))
    }
}
