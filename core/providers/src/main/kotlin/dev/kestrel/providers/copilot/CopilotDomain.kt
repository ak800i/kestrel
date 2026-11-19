package dev.kestrel.providers.copilot

import dev.kestrel.providers.catalog.CatalogLoader
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.RequestAuth
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Copilot's hosts on github.com and on a GHE.com tenant (design D12; research r27). */
object CopilotDomain {
    const val PUBLIC = "github.com"

    /** `https://api.<domain>/copilot_internal/user`, which names the account's API address and its allowance. */
    fun userUrl(domain: String): HttpUrl = "https://api.$domain/copilot_internal/user".toHttpUrl()

    /** The API base when the account names none: `https://api.individual.githubcopilot.com`, or `https://copilot-api.<tenant>`. */
    fun fallbackBase(domain: String): HttpUrl =
        (if (domain == PUBLIC) "https://api.individual.githubcopilot.com" else "https://copilot-api.$domain").toHttpUrl()

    /** Whether Copilot's API may live on [host]: GitHub's proxy, `*.githubcopilot.com`, or for a tenant its own domain. */
    fun trusted(host: String, domain: String): Boolean =
        host == "copilot-proxy.githubusercontent.com" || host.endsWith(".githubcopilot.com") ||
            (domain != PUBLIC && (host == domain || host.endsWith(".$domain")))

    /** [address] as an API base when it is HTTPS on a trusted host, without user, query or fragment; otherwise null. */
    fun accept(address: String, domain: String): HttpUrl? {
        val url = address.trim().toHttpUrlOrNull() ?: return null
        if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) return null
        if (!trusted(url.host, domain)) return null
        return url.newBuilder().encodedPath("/" + url.encodedPath.trim('/')).build()
    }
}

/** The GitHub token in a header: `Bearer` for Copilot's API and its user call, `token` for the allowance (research r27). */
internal class GitHubAuth(private val token: String, private val scheme: String = "Bearer") : RequestAuth {
    override suspend fun headers(request: HttpRequest): Map<String, String> = mapOf("Authorization" to "$scheme $token")

    override val rejected: ProviderError = ProviderError.SignInExpired(CatalogLoader.COPILOT)

    override fun toString(): String = "GitHubAuth(scheme=$scheme)"
}
