package dev.kestrel.providers.copilot

import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.signin.SignInFailed
import dev.kestrel.providers.signin.SignInFailure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl

/**
 * Finds a Copilot account's API address (design D12; research r27): `GET https://api.<domain>/copilot_internal/user` with
 * only `Accept` and the GitHub token as the bearer, and its `endpoints.api`. A missing or empty field gives the fallback
 * base; an address that is not HTTPS on a trusted host ends with "Copilot returned an address that Kestrel does not
 * trust." (UNTRUSTED_ADDRESS).
 */
class CopilotDiscovery(private val http: ProviderHttp) {
    private val json = Json { isLenient = true }

    suspend fun apiBase(token: Credential.GitHubToken): HttpUrl {
        val request = HttpRequest("GET", CopilotDomain.userUrl(token.domain), mapOf("Accept" to "application/json"))
        val reply = http.send(request, GitHubAuth(token.token))
        val body = try {
            json.parseToJsonElement(reply.text()) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        } ?: throw ProviderException(ProviderError.Malformed)
        val api = (body["endpoints"] as? JsonObject)?.get("api")
        if (api == null || api is JsonNull || (api is JsonPrimitive && api.isString && api.content.isEmpty())) {
            return CopilotDomain.fallbackBase(token.domain)
        }
        val address = (api as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw SignInFailed(SignInFailure.UNTRUSTED_ADDRESS)
        return CopilotDomain.accept(address, token.domain) ?: throw SignInFailed(SignInFailure.UNTRUSTED_ADDRESS)
    }
}
