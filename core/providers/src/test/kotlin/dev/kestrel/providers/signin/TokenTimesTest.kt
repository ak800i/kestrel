package dev.kestrel.providers.signin

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class TokenTimesTest {
    @Test
    fun earliestRefreshAtIsReadAsSecondsMillisecondsOrAnIsoTime() {
        assertEquals(1_800_000_000_000L, TokenTimes.earliestRefreshAt(JsonPrimitive(1_800_000_000)))
        assertEquals(1_800_000_000_123L, TokenTimes.earliestRefreshAt(JsonPrimitive(1_800_000_000_123L)))
        assertEquals(1_800_000_000_000L, TokenTimes.earliestRefreshAt(JsonPrimitive("2027-01-15T08:00:00Z")))
        assertEquals(1_800_000_000_000L, TokenTimes.earliestRefreshAt(JsonPrimitive("2027-01-15T09:00:00+01:00")))
        assertNull(TokenTimes.earliestRefreshAt(JsonPrimitive("soon")))
        assertNull(TokenTimes.earliestRefreshAt(JsonArray(emptyList())))
        assertNull(TokenTimes.earliestRefreshAt(JsonNull))
        assertNull(TokenTimes.earliestRefreshAt(null))
    }

    @Test
    fun miniMaxsExpiryIsRelativeSecondsEpochSecondsOrEpochMilliseconds() {
        val now = 1_700_000_000_000L

        assertEquals(now + 7_200_000, TokenTimes.miniMaxTime(7_200, now))
        assertEquals(1_800_000_000_000L, TokenTimes.miniMaxTime(1_800_000_000, now))
        assertEquals(1_800_000_000_123L, TokenTimes.miniMaxTime(1_800_000_000_123L, now))
    }

    @Test
    fun aJwtsExpiryComesFromItsExpClaim() {
        fun jwt(claims: String) = "e30." + Base64.getUrlEncoder().withoutPadding().encodeToString(claims.encodeToByteArray()) + ".c2ln"

        assertEquals(1_800_000_000_000L, TokenTimes.jwtExpiry(jwt("""{"exp":1800000000}""")))
        assertNull(TokenTimes.jwtExpiry(jwt("""{"sub":"a"}""")))
        assertNull(TokenTimes.jwtExpiry("not-a-jwt"))
    }
}
