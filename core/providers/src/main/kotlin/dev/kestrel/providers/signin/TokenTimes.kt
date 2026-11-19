package dev.kestrel.providers.signin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Base64

/** The times that the providers' token answers give in more than one form (design D9). */
object TokenTimes {
    private const val EPOCH_MILLIS_FROM = 1_000_000_000_000L
    private const val EPOCH_SECONDS_FROM = 1_000_000_000L

    /**
     * The life that a new token gets when its expiry has already passed, as after a difference between the phone's clock
     * and the provider's: valid for now, and refreshed early at its first use.
     */
    const val SHORTEST_LIFE_MILLIS = 60_000L

    /** [expiresAt], or [now] plus [SHORTEST_LIFE_MILLIS] when [expiresAt] is not in the future. */
    fun notPast(expiresAt: Long, now: Long): Long = if (expiresAt > now) expiresAt else now + SHORTEST_LIFE_MILLIS

    /**
     * The ChatGPT plan's `earliest_refresh_at`, which OpenAI's pages give no format: a number below 10^12 is epoch
     * seconds, a larger one epoch milliseconds, a string an ISO-8601 time; any other value is null. It only delays the
     * early refresh, so a value that Kestrel cannot read never fails a sign-in or a refresh.
     */
    fun earliestRefreshAt(value: JsonElement?): Long? {
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive.isString) {
            return try {
                OffsetDateTime.parse(primitive.content).toInstant().toEpochMilli()
            } catch (e: DateTimeParseException) {
                try {
                    Instant.parse(primitive.content).toEpochMilli()
                } catch (e: DateTimeParseException) {
                    null
                }
            }
        }
        val number = primitive.doubleOrNull?.toLong() ?: return null
        if (number <= 0) return null
        return if (number < EPOCH_MILLIS_FROM) number * 1000 else number
    }

    /**
     * MiniMax's `expired_in` (research r31 left the unit open): below 10^9 seconds from [now], from 10^9 to 10^12 epoch
     * seconds, and from 10^12 on epoch milliseconds.
     */
    fun miniMaxTime(value: Long, now: Long): Long = when {
        value < EPOCH_SECONDS_FROM -> now + value * 1000
        value < EPOCH_MILLIS_FROM -> value * 1000
        else -> value
    }

    /** The `exp` claim of a JWT, in epoch milliseconds, without checking its signature; null when there is none. */
    fun jwtExpiry(token: String): Long? =
        (jwtClaims(token)?.get("exp") as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.toLong()?.times(1000)

    /** The claims of a JWT, without checking its signature; null when [token] is not a JWT. */
    fun jwtClaims(token: String): JsonObject? {
        val parts = token.split('.')
        if (parts.size != 3) return null
        return try {
            Json.parseToJsonElement(Base64.getUrlDecoder().decode(parts[1]).decodeToString()) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
