package dev.kestrel.providers.signin

import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import kotlinx.coroutines.delay
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * xAI's sign-in (design D9, research r31): OIDC discovery at `https://auth.x.ai`, whose device-authorization and token
 * endpoints must be HTTPS on `x.ai` or a subdomain and are leased on the allowlist through [openHosts] until the flow
 * ends; then RFC 8628 with the Grok CLI's client. `verification_uri_complete` is preferred, and must be HTTPS on `x.ai`
 * too. The token answer must hold a refresh token and an expiry (`expires_in`, or the access token's `exp`); an expiry that
 * has already passed, as after a difference between the clocks, gets [TokenTimes.SHORTEST_LIFE_MILLIS]. The token
 * endpoint's host goes into the profile's hosts. A rate limit, a server error or a network failure during a poll slows
 * the polls down, as `slow_down` does.
 */
class XaiDeviceFlow(
    private val http: ProviderHttp,
    private val openHosts: (Set<String>) -> AutoCloseable,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : DeviceCodeFlow {
    override val signInHosts: Set<String> = setOf("auth.x.ai")

    private var lease: AutoCloseable? = null
    private var tokenEndpoint: HttpUrl? = null
    private var deviceCode: String? = null

    override suspend fun start(): DeviceCodePrompt {
        val reply = http.exchange(SignInHttp.get(XaiAuth.DISCOVERY_URL.toHttpUrl()))
        if (reply.status !in 200..299) throw SignInHttp.failure(reply, http)
        val discovery = SignInHttp.parse(reply) ?: throw ProviderException(ProviderError.Malformed)
        val device = trusted(discovery.string("device_authorization_endpoint"))
        val token = trusted(discovery.string("token_endpoint"))
        val opened = openHosts(setOf(device.host, token.host))
        lease = opened
        try {
            val answer = http.exchange(SignInHttp.form(device, "client_id" to XaiAuth.CLIENT_ID, "scope" to XaiAuth.SCOPE))
            if (answer.status !in 200..299) throw SignInHttp.failure(answer, http)
            val body = SignInHttp.parse(answer) ?: throw ProviderException(ProviderError.Malformed)
            val code = body.string("device_code") ?: throw ProviderException(ProviderError.Malformed)
            val userCode = body.string("user_code")?.trim()?.takeIf { it.isNotEmpty() } ?: throw ProviderException(ProviderError.Malformed)
            val page = trusted(body.string("verification_uri_complete") ?: body.string("verification_uri"))
            val expiresIn = body.number("expires_in")?.takeIf { it > 0 } ?: DEFAULT_EXPIRY_SECONDS
            val interval = maxOf(MIN_INTERVAL_MILLIS, (body.number("interval") ?: DEFAULT_INTERVAL_SECONDS) * 1000)
            tokenEndpoint = token
            deviceCode = code
            return DeviceCodePrompt(userCode, page.toString(), clock() + expiresIn * 1000, interval)
        } catch (e: Throwable) {
            opened.close()
            throw e
        }
    }

    override suspend fun await(prompt: DeviceCodePrompt): SignInResult {
        try {
            val endpoint = checkNotNull(tokenEndpoint) { "start() comes first" }
            val code = checkNotNull(deviceCode)
            var interval = prompt.intervalMillis
            while (true) {
                DevicePolls.pause(prompt, interval, clock, sleep)
                val reply = DevicePolls.send(
                    http,
                    SignInHttp.form(
                        endpoint,
                        "grant_type" to GitHubDeviceFlow.DEVICE_GRANT,
                        "client_id" to XaiAuth.CLIENT_ID,
                        "device_code" to code,
                    ),
                ) { interval = DevicePolls.slower(interval, it) } ?: continue
                val body = SignInHttp.parse(reply) ?: throw ProviderException(ProviderError.Malformed)
                if (reply.status in 200..299) {
                    val access = body.string("access_token") ?: throw SignInFailed(SignInFailure.EXCHANGE_FAILED)
                    val refresh = body.string("refresh_token") ?: throw SignInFailed(SignInFailure.EXCHANGE_FAILED)
                    if (access.isEmpty() || refresh.isEmpty()) throw ProviderException(ProviderError.Malformed)
                    val now = clock()
                    val expiresAt = body.number("expires_in")?.let { now + it * 1000 } ?: TokenTimes.jwtExpiry(access)
                        ?: throw SignInFailed(SignInFailure.EXCHANGE_FAILED)
                    val credential = Credential.OAuthToken(access, refresh, TokenTimes.notPast(expiresAt, now), tokenEndpoint = endpoint.toString())
                    return SignInResult(credential, hosts = setOf(endpoint.host))
                }
                when (body.string("error")) {
                    "authorization_pending" -> Unit
                    "slow_down" -> interval = DevicePolls.slower(interval)
                    "access_denied", "authorization_denied" -> throw SignInFailed(SignInFailure.REFUSED)
                    "expired_token" -> throw SignInFailed(SignInFailure.EXPIRED)
                    else -> throw SignInFailed(SignInFailure.EXCHANGE_FAILED)
                }
            }
        } finally {
            lease?.close()
        }
    }

    /** An address of xAI's sign-in: HTTPS on `x.ai` or a subdomain, or the flow ends. */
    private fun trusted(address: String?): HttpUrl =
        address?.toHttpUrlOrNull()?.takeIf { it.isHttps && hostIn(it.host, XaiAuth.DOMAIN) } ?: throw SignInFailed(SignInFailure.UNTRUSTED_ADDRESS)

    override fun toString(): String = "XaiDeviceFlow"

    private companion object {
        const val DEFAULT_EXPIRY_SECONDS = 300L
        const val DEFAULT_INTERVAL_SECONDS = 5L
        const val MIN_INTERVAL_MILLIS = 1_000L
    }
}
