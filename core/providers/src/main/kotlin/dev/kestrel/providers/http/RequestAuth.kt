package dev.kestrel.providers.http

import kotlinx.serialization.Serializable

/**
 * The credential's part of a request (design D9). [headers] returns fresh headers for each attempt; [refresh] is called
 * once when a request fails with 401; [rejected] is the error for a credential that the provider refused for good.
 */
interface RequestAuth {
    suspend fun headers(request: HttpRequest): Map<String, String>

    suspend fun refresh(): RefreshResult = RefreshResult.NotPossible

    val rejected: ProviderError get() = ProviderError.Unauthorized
}

/** What a refresh achieved (design D9). */
sealed interface RefreshResult {
    /** The new token is stored; the request is sent once more. */
    data object Refreshed : RefreshResult

    /** No network, a timeout, a 429 or a 5xx: the credential stays, and the request fails with [error]. */
    data class FailedForNow(val error: ProviderError) : RefreshResult

    /** The provider refused the refresh for good. */
    data object Expired : RefreshResult

    /** The credential cannot be refreshed. */
    data object NotPossible : RefreshResult
}

/** The header that carries an API key (design D4). */
@Serializable
enum class KeyHeader(val header: String) {
    BEARER("Authorization"),
    X_API_KEY("x-api-key"),
    X_GOOG_API_KEY("x-goog-api-key"),
    API_KEY("api-key"),
}

/** Sends an API key in a header, never in a URL, so that no key can reach a log (design D4). */
class ApiKeyAuth(private val key: String, private val header: KeyHeader) : RequestAuth {
    override suspend fun headers(request: HttpRequest): Map<String, String> =
        mapOf(header.header to if (header == KeyHeader.BEARER) "Bearer $key" else key)

    override fun toString(): String = "ApiKeyAuth(header=$header, keyLength=${key.length})"
}

/** For requests without a credential, such as a local server without a key. */
object NoAuth : RequestAuth {
    override suspend fun headers(request: HttpRequest): Map<String, String> = emptyMap()

    override fun toString(): String = "NoAuth"
}
