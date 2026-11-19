package dev.kestrel.providers.http

import kotlinx.coroutines.runBlocking
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RequestAuthTest {
    private val request = HttpRequest("GET", "https://api.example.com/v1/models".toHttpUrl())

    @Test
    fun eachKeyHeaderCarriesTheKey() = runBlocking {
        assertEquals(mapOf("Authorization" to "Bearer test-key"), ApiKeyAuth("test-key", KeyHeader.BEARER).headers(request))
        assertEquals(mapOf("x-api-key" to "test-key"), ApiKeyAuth("test-key", KeyHeader.X_API_KEY).headers(request))
        assertEquals(mapOf("x-goog-api-key" to "test-key"), ApiKeyAuth("test-key", KeyHeader.X_GOOG_API_KEY).headers(request))
        assertEquals(mapOf("api-key" to "test-key"), ApiKeyAuth("test-key", KeyHeader.API_KEY).headers(request))
        assertEquals(emptyMap<String, String>(), NoAuth.headers(request))
    }

    @Test
    fun aKeyCannotBeRefreshedAndIsRejectedAsUnauthorized() = runBlocking {
        val auth = ApiKeyAuth("test-key", KeyHeader.BEARER)

        assertEquals(RefreshResult.NotPossible, auth.refresh())
        assertEquals(ProviderError.Unauthorized, auth.rejected)
    }

    @Test
    fun nothingThatCarriesAKeyOrABodyPrintsIt() {
        val printed = listOf(
            ApiKeyAuth("secret-key", KeyHeader.BEARER),
            HttpRequest("POST", request.url, mapOf("Authorization" to "Bearer secret-key"), "secret prompt".encodeToByteArray()),
            HttpReply(200, headersOf("Set-Cookie", "secret"), "secret reply".encodeToByteArray()),
            SseEvent("response.completed", "secret reply"),
            ProviderException(ProviderError.QuotaOrPolicy(403, "secret_code_is_fine")),
        ).joinToString()

        assertFalse(printed, Regex("secret(-key| prompt| reply)|Cookie").containsMatchIn(printed))
        assertEquals(
            "ApiKeyAuth(header=BEARER, keyLength=10), HttpRequest(method=POST, headers=1, bodyBytes=13), " +
                "HttpReply(status=200, headers=1, bodyBytes=12), SseEvent(event=response.completed, dataLength=12), " +
                "dev.kestrel.providers.http.ProviderException: QuotaOrPolicy(status=403, code=secret_code_is_fine)",
            printed,
        )
    }
}
