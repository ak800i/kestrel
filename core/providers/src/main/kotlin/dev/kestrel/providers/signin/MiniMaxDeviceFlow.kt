package dev.kestrel.providers.signin

import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import kotlinx.coroutines.delay
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

/**
 * MiniMax's M Plan sign-in (design D9, research r31): MiniMax's user-code grant with PKCE and a `state` that must come back
 * unchanged, through the client that is probably OpenClaw's registration (a borrowed identity, design D21). Polls decide
 * by the body's `status`: `success`, `error`, and anything else pending. MiniMax has no `slow_down`, and research left two
 * units open: an `interval` below 100 is seconds and from 100 on milliseconds, with a floor of 2 s; `expired_in` is read
 * by [TokenTimes.miniMaxTime]. A rate limit, a server error or a network failure during a poll adds 5 s to the interval,
 * or waits for a longer `Retry-After`. A `resource_url` becomes the base URL only when it is HTTPS on the sign-in's own
 * region (`minimax.io` or `minimaxi.com`), without user, query or fragment; otherwise the region's own Anthropic route is
 * used.
 */
class MiniMaxDeviceFlow(
    private val region: MiniMaxRegion,
    private val http: ProviderHttp,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : DeviceCodeFlow {
    override val signInHosts: Set<String> = setOf(region.accountHost)

    private var verifier: String? = null

    override suspend fun start(): DeviceCodePrompt {
        val pkce = Pkce.verifier()
        val state = Pkce.state()
        val headers = mapOf("Accept" to "application/json", "x-request-id" to UUID.randomUUID().toString())
        val reply = http.exchange(
            SignInHttp.form(
                "https://${region.accountHost}/oauth2/device/code".toHttpUrl(),
                "response_type" to "code",
                "client_id" to MiniMaxAuth.CLIENT_ID,
                "scope" to MiniMaxAuth.SCOPE,
                "code_challenge" to Pkce.challenge(pkce),
                "code_challenge_method" to "S256",
                "state" to state,
                headers = headers,
            ),
        )
        if (reply.status !in 200..299) throw SignInHttp.failure(reply, http)
        val body = SignInHttp.parse(reply) ?: throw ProviderException(ProviderError.Malformed)
        if (body.string("state") != state) throw SignInFailed(SignInFailure.EXCHANGE_FAILED)
        val userCode = body.string("user_code")?.trim()?.takeIf { it.isNotEmpty() } ?: throw ProviderException(ProviderError.Malformed)
        val page = body.string("verification_uri")?.toHttpUrlOrNull()?.takeIf { it.isHttps && miniMax(it.host) }
            ?: throw SignInFailed(SignInFailure.UNTRUSTED_ADDRESS)
        val now = clock()
        val expiresAt = body.number("expired_in")?.takeIf { it >= 1 }?.let { TokenTimes.miniMaxTime(it, now) }
            ?: throw ProviderException(ProviderError.Malformed)
        verifier = pkce
        return DeviceCodePrompt(userCode, page.toString(), expiresAt, interval(body.number("interval")))
    }

    override suspend fun await(prompt: DeviceCodePrompt): SignInResult {
        val pkce = checkNotNull(verifier) { "start() comes first" }
        var interval = prompt.intervalMillis
        while (true) {
            DevicePolls.pause(prompt, interval, clock, sleep)
            val reply = DevicePolls.send(
                http,
                SignInHttp.form(
                    region.tokenUrl.toHttpUrl(),
                    "grant_type" to USER_CODE_GRANT,
                    "client_id" to MiniMaxAuth.CLIENT_ID,
                    "user_code" to prompt.userCode,
                    "code_verifier" to pkce,
                ),
            ) { interval = DevicePolls.slower(interval, it) } ?: continue
            // Design D9 and OpenClaw's poll parser: another refused HTTP status or `status: "error"` ends the sign-in,
            // `success` gives the token, and any other status is pending; only the refresh reads `base_resp`.
            if (reply.status !in 200..299) throw SignInFailed(SignInFailure.REFUSED)
            val body = SignInHttp.parse(reply) ?: throw ProviderException(ProviderError.Malformed)
            when (body.string("status")) {
                "success" -> Unit
                "error" -> throw SignInFailed(SignInFailure.REFUSED)
                else -> continue
            }
            val access = body.string("access_token") ?: throw SignInFailed(SignInFailure.EXCHANGE_FAILED)
            val refresh = body.string("refresh_token") ?: throw SignInFailed(SignInFailure.EXCHANGE_FAILED)
            if (access.isEmpty() || refresh.isEmpty()) throw ProviderException(ProviderError.Malformed)
            val now = clock()
            // A time that has passed, as after a clock that runs ahead or a deadline in another unit, still gives a short life.
            val expiresAt = body.number("expired_in")?.let { TokenTimes.notPast(TokenTimes.miniMaxTime(it, now), now) }
            val base = body.string("resource_url")?.toHttpUrlOrNull()?.takeIf(::ownRoute)?.toString() ?: region.apiBase
            val credential = Credential.OAuthToken(access, refresh, expiresAt, tokenEndpoint = region.tokenUrl, region = region.name.lowercase())
            return SignInResult(credential, baseUrl = base, hosts = setOf(region.accountHost, base.toHttpUrl().host))
        }
    }

    private fun interval(value: Long?): Long {
        val millis = when {
            value == null -> MIN_INTERVAL_MILLIS
            value < 100 -> value * 1000
            else -> value
        }
        return maxOf(MIN_INTERVAL_MILLIS, millis)
    }

    private fun miniMax(host: String): Boolean = hostIn(host, "minimax.io") || hostIn(host, "minimaxi.com")

    /** Whether [url] may be the profile's base: HTTPS on the sign-in's own region, without user, query or fragment. */
    private fun ownRoute(url: HttpUrl): Boolean =
        url.isHttps && hostIn(url.host, region.accountHost.substringAfter('.')) && url.username.isEmpty() && url.password.isEmpty() &&
            url.query == null && url.fragment == null

    override fun toString(): String = "MiniMaxDeviceFlow(region=$region)"

    private companion object {
        const val USER_CODE_GRANT = "urn:ietf:params:oauth:grant-type:user_code"
        const val MIN_INTERVAL_MILLIS = 2_000L
    }
}
