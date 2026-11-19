package dev.kestrel.providers.signin

import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.profiles.ChatGptRegistration
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The ChatGPT plan's sign-in: OpenAI's "Sign in with ChatGPT" for open-source apps, which registers Kestrel as its own app
 * (design D9; research r46; OpenAI's "Registration and sign-in", "Token reference", "Accounts and sessions" and "Errors
 * and recovery"). It borrows nothing.
 *
 * A first sign-in ([registration] null) sends `client_id=dynamic_agent_client` with `agent_name_hint=Kestrel` and the
 * installation's [hostId], and its callback must return the issued client ID; a returning sign-in sends the kept issued
 * ID, and its callback may omit it but never name another. The redirect is `http://127.0.0.1:<port>/auth/callback`, on
 * port 1455 when it is free, never `localhost`. The flow checks the discovery document, exchanges the code with PKCE,
 * checks the ID token, refuses another account's `sub`, and requires the plan scope `chatgpt.tokens.use.direct`; without
 * it the registration is kept and the owner may allow plan use again ([consent] adds `prompt=consent`). Kestrel keeps no
 * ID token and no e-mail address, so it sends neither `id_token_hint` nor `login_hint`.
 */
class ChatGptPlanFlow(
    private val hostId: String,
    private val registration: ChatGptRegistration?,
    private val consent: Boolean = false,
    private val http: ProviderHttp,
    private val clock: () -> Long = System::currentTimeMillis,
) : BrowserFlow {
    override val signInHosts: Set<String> = setOf(ChatGptPlanAuth.HOST)

    override suspend fun run(loopback: LoopbackListener, openBrowser: (String) -> Unit): SignInResult {
        val discovery = discover(http)
        val session = loopback.open(LoopbackAddresses.IPV4, ChatGptPlanAuth.PREFERRED_PORT, ChatGptPlanAuth.CALLBACK_PATH)
            ?: loopback.open(LoopbackAddresses.IPV4, null, ChatGptPlanAuth.CALLBACK_PATH)
            // No loopback port opened, so the browser was never asked: the sign-in did not finish.
            ?: throw SignInFailed(SignInFailure.EXCHANGE_FAILED, registration)
        val redirect = "http://127.0.0.1:${session.port}${ChatGptPlanAuth.CALLBACK_PATH}"
        val verifier = Pkce.verifier()
        val state = Pkce.state()
        val nonce = Pkce.nonce()
        val callback = session.use {
            openBrowser(authorizeUrl(redirect, Pkce.challenge(verifier), state, nonce).toString())
            session.await(state, CALLBACK_WAIT_MILLIS) ?: throw SignInFailed(SignInFailure.NO_CALLBACK)
        }.query
        callback["error"]?.let { throw SignInFailed(if (it == "access_denied") SignInFailure.CANCELLED else SignInFailure.REGISTRATION_REFUSED) }
        val returned = callback["client_id"]
        val clientId = if (registration == null) {
            // An issued client ID has OpenAI's form, as OpenClaw checks; the dynamic client's placeholder never has it, and a
            // client ID that the callback repeats is missing, because the loopback server leaves repeated fields out.
            returned?.takeIf { ISSUED_CLIENT_ID.matches(it) } ?: throw SignInFailed(SignInFailure.REGISTRATION_INCOMPLETE)
        } else {
            if (returned != null && returned != registration.clientId) throw SignInFailed(SignInFailure.OTHER_ACCOUNT)
            registration.clientId
        }
        // A retry in the same add flow reuses the issued client ID and never registers Kestrel a second time.
        val kept = ChatGptRegistration(clientId, registration?.subject)
        try {
            val code = callback["code"] ?: throw SignInFailed(SignInFailure.EXCHANGE_FAILED, kept)
            val reply = http.exchange(
                SignInHttp.form(
                    ChatGptPlanAuth.TOKEN_URL.toHttpUrl(),
                    "grant_type" to "authorization_code",
                    "client_id" to clientId,
                    "code" to code,
                    "code_verifier" to verifier,
                    "redirect_uri" to redirect,
                    "resource" to ChatGptPlanAuth.RESOURCE,
                ),
            )
            if (SignInHttp.temporary(reply.status)) throw SignInHttp.failure(reply, http)
            val body = SignInHttp.parse(reply)
            if (reply.status !in 200..299) {
                throw when (SignInHttp.errorCode(body)) {
                    "invalid_client", "unauthorized_client" -> SignInFailed(SignInFailure.REGISTRATION_REFUSED)
                    else -> SignInFailed(SignInFailure.EXCHANGE_FAILED, kept)
                }
            }
            // OpenAI's token answer is a bearer token; any other type is not one that Kestrel can send.
            if (!body?.string("token_type").equals("bearer", ignoreCase = true)) throw SignInFailed(SignInFailure.EXCHANGE_FAILED, kept)
            val access = body?.string("access_token") ?: throw SignInFailed(SignInFailure.EXCHANGE_FAILED, kept)
            val refresh = body.string("refresh_token") ?: throw SignInFailed(SignInFailure.EXCHANGE_FAILED, kept)
            // An empty token is an answer that Kestrel cannot read; the catch below keeps the issued client ID for it.
            if (access.isEmpty() || refresh.isEmpty()) throw ProviderException(ProviderError.Malformed)
            val idToken = body.string("id_token") ?: throw SignInFailed(SignInFailure.EXCHANGE_FAILED, kept)
            val claims = checkIdToken(idToken, discovery, clientId, nonce) ?: throw SignInFailed(SignInFailure.EXCHANGE_FAILED, kept)
            if (registration?.subject != null && registration.subject != claims.subject) throw SignInFailed(SignInFailure.OTHER_ACCOUNT)
            val registered = ChatGptRegistration(clientId, claims.subject)
            // RFC 6749 lets the token answer leave out a scope that equals the request's, and the callback carries it too.
            val scope = body.string("scope") ?: callback["scope"]
            if (ChatGptPlanAuth.PLAN_SCOPE !in scope.orEmpty().split(' ')) {
                throw SignInFailed(SignInFailure.PLAN_USE_NOT_ALLOWED, registered)
            }
            val now = clock()
            val credential = Credential.OAuthToken(
                access = access,
                refresh = refresh,
                expiresAtMillis = body.number("expires_in")?.let { now + it * 1000 },
                earliestRefreshAtMillis = TokenTimes.earliestRefreshAt(body["earliest_refresh_at"]),
                clientId = clientId,
            )
            return SignInResult(credential, hosts = setOf(ChatGptPlanAuth.API_HOST, ChatGptPlanAuth.HOST), registration = registered)
        } catch (e: ProviderException) {
            // No network, a 429 or a 5xx at the exchange or the key set: "Try again" must reuse the issued client ID (design D9).
            throw SignInFailed(SignInFailure.EXCHANGE_FAILED, kept)
        }
    }

    private fun authorizeUrl(redirect: String, challenge: String, state: String, nonce: String): HttpUrl =
        ChatGptPlanAuth.AUTHORIZE_URL.toHttpUrl().newBuilder().apply {
            addQueryParameter("client_id", registration?.clientId ?: ChatGptPlanAuth.DYNAMIC_CLIENT)
            if (registration == null) addQueryParameter("agent_name_hint", ChatGptPlanAuth.AGENT_NAME)
            addQueryParameter("ext_agent_host_id", hostId)
            addQueryParameter("response_type", "code")
            addQueryParameter("redirect_uri", redirect)
            addQueryParameter("scope", ChatGptPlanAuth.SCOPE)
            addQueryParameter("resource", ChatGptPlanAuth.RESOURCE)
            addQueryParameter("state", state)
            addQueryParameter("nonce", nonce)
            addQueryParameter("code_challenge_method", "S256")
            addQueryParameter("code_challenge", challenge)
            if (consent) addQueryParameter("prompt", "consent")
        }.build()

    /** The ID token's claims with OpenAI's key set, fetched once more when the token's `kid` is not in it. */
    private suspend fun checkIdToken(idToken: String, discovery: Discovery, clientId: String, nonce: String): IdTokenClaims? {
        var keys = keySet(discovery.jwks)
        IdTokens.verify(idToken, keys, discovery.issuer, clientId, nonce, clock())?.let { return it }
        if (IdTokens.keyId(idToken) in kids(keys)) return null
        keys = keySet(discovery.jwks)
        return IdTokens.verify(idToken, keys, discovery.issuer, clientId, nonce, clock())
    }

    private suspend fun keySet(url: HttpUrl): JsonObject {
        val reply = http.exchange(SignInHttp.get(url))
        if (reply.status !in 200..299) throw SignInHttp.failure(reply, http)
        return SignInHttp.parse(reply) ?: throw ProviderException(ProviderError.Malformed)
    }

    private fun kids(keys: JsonObject): List<String?> =
        (keys["keys"] as? JsonArray).orEmpty().map { (it as? JsonObject)?.string("kid") }

    override fun toString(): String = "ChatGptPlanFlow(registration=$registration, consent=$consent)"

    /** The addresses of OpenAI's discovery document that Kestrel trusts. */
    internal class Discovery(val issuer: String, val jwks: HttpUrl, val revocation: HttpUrl)

    companion object {
        private const val CALLBACK_WAIT_MILLIS = 5 * 60_000L
        private val REVOKE_PAUSES = listOf(1_000L, 3_000L)
        private val ISSUED_CLIENT_ID = Regex("^oaiapp_[A-Za-z0-9_-]+$")

        /**
         * Reads `https://auth.openai.com/.well-known/openid-configuration` and trusts it only when its `issuer` is
         * `https://auth.openai.com` and its `jwks_uri` and `revocation_endpoint` are HTTPS on `auth.openai.com`;
         * otherwise "OpenAI's sign-in returned an address that Kestrel does not trust." (design D9).
         */
        internal suspend fun discover(http: ProviderHttp): Discovery {
            val reply = http.exchange(SignInHttp.get(ChatGptPlanAuth.DISCOVERY_URL.toHttpUrl()))
            if (reply.status !in 200..299) throw SignInHttp.failure(reply, http)
            val body = SignInHttp.parse(reply) ?: throw ProviderException(ProviderError.Malformed)
            fun trusted(key: String) = body.string(key)?.toHttpUrlOrNull()?.takeIf { it.isHttps && it.host == ChatGptPlanAuth.HOST }
            val jwks = trusted("jwks_uri")
            val revocation = trusted("revocation_endpoint")
            if (body.string("issuer") != ChatGptPlanAuth.ISSUER || jwks == null || revocation == null) {
                throw SignInFailed(SignInFailure.UNTRUSTED_ADDRESS)
            }
            return Discovery(ChatGptPlanAuth.ISSUER, jwks, revocation)
        }

        /**
         * Signs out (design D9; OpenAI's "Accounts and sessions"): revokes [token]'s refresh token at the checked
         * revocation endpoint, with the issued client ID, while `auth.openai.com` is still on the allowlist. An empty 200
         * is success; a network failure or a 5xx is tried again twice, after 1 s and 3 s. Returns whether OpenAI confirmed
         * it; the caller deletes the profile either way, and the registration and the host ID stay.
         */
        suspend fun revoke(http: ProviderHttp, token: Credential.OAuthToken, pause: suspend (Long) -> Unit = { delay(it) }): Boolean {
            val clientId = token.clientId ?: return false
            val refresh = token.refresh ?: return false
            for (attempt in 0..REVOKE_PAUSES.size) {
                if (attempt > 0) pause(REVOKE_PAUSES[attempt - 1])
                try {
                    val endpoint = discover(http).revocation
                    val reply = http.exchange(
                        SignInHttp.form(endpoint, "token" to refresh, "token_type_hint" to "refresh_token", "client_id" to clientId),
                    )
                    if (reply.status in 200..299) return true
                    if (reply.status !in 500..599) return false
                } catch (e: ProviderException) {
                    // Only no network or a server error, also of the discovery document, is tried again (design D9).
                    if (e.error !is ProviderError.ServerError && e.error != ProviderError.Unreachable && e.error != ProviderError.Timeout) {
                        return false
                    }
                } catch (e: SignInFailed) {
                    return false
                }
            }
            return false
        }
    }
}
