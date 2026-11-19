package dev.kestrel.providers.signin

import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Copilot's sign-in (design D9 and D12, research r27): GitHub's device flow with GitHub's own OAuth client, on github.com
 * or a GHE.com tenant. The verification address must be `https://<domain>/login/device`, and the page shows that fixed
 * address. Polls sleep first, at least 1 s apart and by default every 5 s; each `slow_down`, rate limit, server error or
 * network failure adds 5 s, or raises the interval to a returned `interval` or `Retry-After` when that is larger. The
 * token never expires on its own and is the bearer of every Copilot call; `api.<domain>` goes into the profile's hosts.
 */
class GitHubDeviceFlow(
    private val domain: String,
    private val http: ProviderHttp,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : DeviceCodeFlow {
    init {
        require(validDomain(domain)) { "Not github.com or a GHE.com tenant" }
    }

    override val signInHosts: Set<String> = setOf(domain)

    private var deviceCode: String? = null

    override suspend fun start(): DeviceCodePrompt {
        val reply = http.exchange(SignInHttp.form("https://$domain/login/device/code".toHttpUrl(), "client_id" to CLIENT_ID, "scope" to SCOPE))
        if (reply.status !in 200..299) throw SignInHttp.failure(reply, http)
        val body = SignInHttp.parse(reply) ?: throw ProviderException(ProviderError.Malformed)
        val code = body.string("device_code") ?: throw ProviderException(ProviderError.Malformed)
        val userCode = body.string("user_code")?.trim()?.takeIf { it.length in 1..64 } ?: throw ProviderException(ProviderError.Malformed)
        val expiresIn = body.number("expires_in")?.takeIf { it > 0 } ?: throw ProviderException(ProviderError.Malformed)
        val interval = body.number("interval")?.takeIf { it >= 0 } ?: DEFAULT_INTERVAL_SECONDS
        val page = body.string("verification_uri")?.toHttpUrlOrNull()
        if (page == null || !page.isHttps || page.host != domain || page.encodedPath != "/login/device" || page.username.isNotEmpty() || page.password.isNotEmpty()) {
            throw SignInFailed(SignInFailure.UNTRUSTED_ADDRESS)
        }
        deviceCode = code
        return DeviceCodePrompt(userCode, "https://$domain/login/device", clock() + expiresIn * 1000, maxOf(MIN_INTERVAL_MILLIS, interval * 1000))
    }

    override suspend fun await(prompt: DeviceCodePrompt): SignInResult {
        val code = checkNotNull(deviceCode) { "start() comes first" }
        var interval = prompt.intervalMillis
        while (true) {
            DevicePolls.pause(prompt, interval, clock, sleep)
            val reply = DevicePolls.send(
                http,
                SignInHttp.form(
                    "https://$domain/login/oauth/access_token".toHttpUrl(),
                    "client_id" to CLIENT_ID,
                    "device_code" to code,
                    "grant_type" to DEVICE_GRANT,
                ),
            ) { interval = DevicePolls.slower(interval, it) } ?: continue
            val body = SignInHttp.parse(reply) ?: throw ProviderException(ProviderError.Malformed)
            body.string("access_token")?.let { token ->
                if (token.isEmpty()) throw ProviderException(ProviderError.Malformed)
                return SignInResult(Credential.GitHubToken(token, domain), hosts = setOf("api.$domain"))
            }
            when (body.string("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> interval = DevicePolls.slower(interval, body.number("interval")?.times(1000))
                "expired_token" -> throw SignInFailed(SignInFailure.EXPIRED)
                "access_denied" -> throw SignInFailed(SignInFailure.REFUSED)
                else -> throw SignInFailed(SignInFailure.EXCHANGE_FAILED)
            }
        }
    }

    override fun toString(): String = "GitHubDeviceFlow(tenant=${domain != PUBLIC})"

    companion object {
        /** GitHub's own OAuth client, which OpenClaw uses: a borrowed identity (design D21). */
        const val CLIENT_ID = "Iv1.b507a08c87ecfe98"
        const val SCOPE = "read:user"
        const val PUBLIC = "github.com"
        const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
        private val TENANT = Regex("^[a-z0-9-]+\\.ghe\\.com$")
        private const val DEFAULT_INTERVAL_SECONDS = 5L
        private const val MIN_INTERVAL_MILLIS = 1_000L

        /** github.com, or a GHE.com tenant such as `acme.ghe.com` (research r27). */
        fun validDomain(domain: String): Boolean = domain == PUBLIC || TENANT.matches(domain)

        /** The tenant that the owner typed, as `acme`, `acme.ghe.com` or `https://acme.ghe.com/`, or null when it is not one. */
        fun tenant(input: String): String? {
            val name = input.trim().lowercase().removePrefix("https://").removeSuffix("/")
            val domain = if (name.endsWith(".ghe.com")) name else "$name.ghe.com"
            return domain.takeIf { TENANT.matches(it) }
        }
    }
}
