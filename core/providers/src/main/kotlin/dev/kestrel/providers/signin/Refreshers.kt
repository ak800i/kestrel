package dev.kestrel.providers.signin

import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.http.HttpReply
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** What a refresh of an OAuth token gave (design D9); CredentialManager turns it into a RefreshResult. */
sealed interface Refresh {
    /** The new token, which CredentialManager stores before it releases the credential's lock. */
    data class Done(val token: Credential.OAuthToken) : Refresh

    /** No network, a timeout, a 429 or a 5xx: the sign-in stays as it is. */
    data class ForNow(val error: ProviderError) : Refresh

    /** The provider refused the refresh for good; [writeBack] is the credential to keep instead, if any. */
    data class Refused(val writeBack: Credential.OAuthToken? = null) : Refresh
}

/** Refreshes one provider's OAuth token. */
fun interface TokenRefresher {
    suspend fun refresh(token: Credential.OAuthToken): Refresh
}

/** Sends [request], or returns the transport failure as a [Refresh.ForNow]. */
private suspend fun ProviderHttp.tryExchange(request: HttpRequest): Pair<HttpReply?, Refresh?> =
    try {
        exchange(request) to null
    } catch (e: ProviderException) {
        null to Refresh.ForNow(e.error)
    }

/**
 * The ChatGPT plan's refresh (design D9; OpenAI's "Accounts and sessions"): a form POST with the issued client ID, the
 * refresh token and the resource, without a scope. Each refresh rotates the refresh token. `invalid_client` clears the
 * client ID, so that the next sign-in registers Kestrel again; every other refusal (`invalid_grant`,
 * `invalid_refresh_token`, `token_expired`, `refresh_token_expired`, `refresh_token_invalidated`, `refresh_token_reused`)
 * keeps it for the next sign-in.
 */
class ChatGptPlanRefresher(private val http: ProviderHttp, private val clock: () -> Long = System::currentTimeMillis) : TokenRefresher {
    override suspend fun refresh(token: Credential.OAuthToken): Refresh {
        val clientId = token.clientId ?: return Refresh.Refused()
        val refreshToken = token.refresh ?: return Refresh.Refused()
        val request = SignInHttp.form(
            ChatGptPlanAuth.TOKEN_URL.toHttpUrl(),
            "grant_type" to "refresh_token",
            "client_id" to clientId,
            "refresh_token" to refreshToken,
            "resource" to ChatGptPlanAuth.RESOURCE,
        )
        val (reply, failed) = http.tryExchange(request)
        if (reply == null) return failed!!
        // A 429 of auth.openai.com is not the plan's usage limit, which only a request reports (design D17), so it is a
        // server error for now.
        if (reply.status == 429) return Refresh.ForNow(ProviderError.ServerError(503))
        if (SignInHttp.temporary(reply.status)) return Refresh.ForNow(SignInHttp.failure(reply).error)
        val body = SignInHttp.parse(reply)
        if (reply.status !in 200..299) {
            return if (body?.string("error") == "invalid_client") Refresh.Refused(token.copy(clientId = null)) else Refresh.Refused()
        }
        val access = body?.string("access_token") ?: return Refresh.ForNow(ProviderError.Malformed)
        val now = clock()
        return Refresh.Done(
            token.copy(
                access = access,
                refresh = body.string("refresh_token") ?: refreshToken,
                expiresAtMillis = body.number("expires_in")?.let { now + it * 1000 },
                earliestRefreshAtMillis = TokenTimes.earliestRefreshAt(body["earliest_refresh_at"]),
            ),
        )
    }
}

/**
 * xAI's refresh (design D9, research r31): a form POST to the token endpoint that the sign-in discovered, which must be
 * HTTPS on `x.ai`. `invalid_request`, `access_denied` and `invalid_grant` are final; a 429 or a 5xx keeps the sign-in,
 * and a 403 with `cf-mitigated: challenge` is tried again up to 3 times, 250 ms apart.
 */
class XaiRefresher(
    private val http: ProviderHttp,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : TokenRefresher {
    override suspend fun refresh(token: Credential.OAuthToken): Refresh {
        val endpoint = token.tokenEndpoint?.toHttpUrlOrNull()?.takeIf { it.isHttps && hostIn(it.host, XaiAuth.DOMAIN) }
            ?: return Refresh.Refused()
        val refreshToken = token.refresh ?: return Refresh.Refused()
        val request = SignInHttp.form(
            endpoint,
            "grant_type" to "refresh_token",
            "client_id" to XaiAuth.CLIENT_ID,
            "refresh_token" to refreshToken,
        )
        var challenges = 0
        while (true) {
            val (reply, failed) = http.tryExchange(request)
            if (reply == null) return failed!!
            if (reply.status == 403 && reply.headers["cf-mitigated"].equals("challenge", ignoreCase = true)) {
                if (++challenges > CHALLENGE_RETRIES) return Refresh.ForNow(ProviderError.QuotaOrPolicy(403))
                sleep(CHALLENGE_PAUSE_MILLIS)
                continue
            }
            if (SignInHttp.temporary(reply.status)) return Refresh.ForNow(SignInHttp.failure(reply).error)
            val body = SignInHttp.parse(reply)
            if (reply.status !in 200..299) return Refresh.Refused()
            val access = body?.string("access_token") ?: return Refresh.ForNow(ProviderError.Malformed)
            val expiresAt = body.number("expires_in")?.let { clock() + it * 1000 } ?: TokenTimes.jwtExpiry(access)
            return Refresh.Done(token.copy(access = access, refresh = body.string("refresh_token") ?: refreshToken, expiresAtMillis = expiresAt))
        }
    }

    private companion object {
        const val CHALLENGE_RETRIES = 3
        const val CHALLENGE_PAUSE_MILLIS = 250L
    }
}

/**
 * MiniMax's refresh (design D9, research r31): a form POST to the token endpoint of the account host that signed in, which
 * must be one of MiniMax's two and, when [region] is given, the profile's region's: a token of the other region is
 * refused, so that the owner signs in again. A body with `status: "error"` or a `base_resp.status_code` other than 0 is
 * final; without a new refresh token the old one stays.
 */
class MiniMaxRefresher(
    private val http: ProviderHttp,
    private val clock: () -> Long = System::currentTimeMillis,
    private val region: MiniMaxRegion? = null,
) : TokenRefresher {
    override suspend fun refresh(token: Credential.OAuthToken): Refresh {
        val endpoint = token.tokenEndpoint?.toHttpUrlOrNull()
            ?.takeIf { url -> url.isHttps && MiniMaxRegion.entries.any { it.accountHost == url.host } }
            ?.takeIf { region == null || it.toString() == region.tokenUrl }
            ?: return Refresh.Refused()
        val refreshToken = token.refresh ?: return Refresh.Refused()
        val request = SignInHttp.form(
            endpoint,
            "grant_type" to "refresh_token",
            "client_id" to MiniMaxAuth.CLIENT_ID,
            "refresh_token" to refreshToken,
        )
        val (reply, failed) = http.tryExchange(request)
        if (reply == null) return failed!!
        if (SignInHttp.temporary(reply.status)) return Refresh.ForNow(SignInHttp.failure(reply).error)
        val body = SignInHttp.parse(reply)
        if (reply.status !in 200..299 || body == null || MiniMaxAuth.failed(body)) return Refresh.Refused()
        val access = body.string("access_token") ?: return Refresh.ForNow(ProviderError.Malformed)
        val now = clock()
        return Refresh.Done(
            token.copy(
                access = access,
                refresh = body.string("refresh_token") ?: refreshToken,
                expiresAtMillis = body.number("expired_in")?.let { TokenTimes.miniMaxTime(it, now) },
            ),
        )
    }
}
