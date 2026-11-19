package dev.kestrel.providers.signin

import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

/**
 * MiniMax's M Plan sign-in (design D9, research r31): MiniMax's user-code grant with PKCE and a `state` that must come back
 * unchanged, through the client that is probably OpenClaw's registration (a borrowed identity, design D21). Polls decide
 * by the body's `status`: `success`, `error`, and anything else pending. MiniMax has no `slow_down`, and research left two
 * units open: an `interval` below 100 is seconds and from 100 on milliseconds, with a floor of 2 s; `expired_in` is read
 * by [TokenTimes.miniMaxTime]. A `resource_url` becomes the base URL only when it is HTTPS on `minimax.io` or
 * `minimaxi.com`; otherwise the region's own Anthropic route is used.
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
        if (reply.status !in 200..299) throw SignInHttp.failure(reply)
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
        while (true) {
            sleep(prompt.intervalMillis)
            val now = clock()
            if (now >= prompt.expiresAtMillis) throw SignInFailed(SignInFailure.EXPIRED)
            val reply = http.exchange(
                SignInHttp.form(
                    region.tokenUrl.toHttpUrl(),
                    "grant_type" to USER_CODE_GRANT,
                    "client_id" to MiniMaxAuth.CLIENT_ID,
                    "user_code" to prompt.userCode,
                    "code_verifier" to pkce,
                ),
            )
            if (SignInHttp.temporary(reply.status)) throw SignInHttp.failure(reply)
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
            val expiresAt = body.number("expired_in")?.let { TokenTimes.miniMaxTime(it, clock()) }
            val base = body.string("resource_url")?.toHttpUrlOrNull()?.takeIf { it.isHttps && miniMax(it.host) }?.toString() ?: region.apiBase
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

    override fun toString(): String = "MiniMaxDeviceFlow(region=$region)"

    private companion object {
        const val USER_CODE_GRANT = "urn:ietf:params:oauth:grant-type:user_code"
        const val MIN_INTERVAL_MILLIS = 2_000L
    }
}
