package dev.kestrel.providers.signin

import dev.kestrel.providers.credentials.ApiKeys
import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.http.ProviderHttp
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * OpenRouter's browser sign-in (design D9 and D10, research r32): PKCE that OpenRouter documents for any app, without a
 * client registration, so it borrows nothing. The redirect is `http://localhost:3000/openrouter-oauth/callback`, or the
 * same path on a free port, because OpenRouter accepts any loopback port; the server listens on both loopback addresses,
 * because Chrome tries `::1` first for `localhost`. The code is exchanged for an API key, which has no refresh.
 */
class OpenRouterBrowserFlow(private val http: ProviderHttp) : BrowserFlow {
    override val signInHosts: Set<String> = setOf("openrouter.ai")

    override suspend fun run(loopback: LoopbackListener, openBrowser: (String) -> Unit): SignInResult {
        val session = loopback.open(LoopbackAddresses.BOTH, PREFERRED_PORT, PATH)
            ?: loopback.open(LoopbackAddresses.BOTH, null, PATH)
            // No loopback port opened, so the browser was never asked: the sign-in did not finish.
            ?: throw SignInFailed(SignInFailure.EXCHANGE_FAILED)
        val verifier = Pkce.verifier()
        val state = Pkce.state()
        val callback = session.use {
            val redirect = "http://localhost:${session.port}$PATH".toHttpUrl().newBuilder().addQueryParameter("state", state).build()
            val page = "https://openrouter.ai/auth".toHttpUrl().newBuilder()
                .addQueryParameter("callback_url", redirect.toString())
                .addQueryParameter("code_challenge", Pkce.challenge(verifier))
                .addQueryParameter("code_challenge_method", "S256")
                .build()
            openBrowser(page.toString())
            session.await(state, CALLBACK_WAIT_MILLIS) ?: throw SignInFailed(SignInFailure.NO_CALLBACK)
        }.query
        val code = callback["code"] ?: throw SignInFailed(SignInFailure.REFUSED)
        val body = buildJsonObject {
            put("code", JsonPrimitive(code))
            put("code_verifier", JsonPrimitive(verifier))
            put("code_challenge_method", JsonPrimitive("S256"))
        }
        val reply = http.exchange(SignInHttp.json("https://openrouter.ai/api/v1/auth/keys".toHttpUrl(), body))
        if (SignInHttp.temporary(reply.status)) throw SignInHttp.failure(reply, http)
        if (reply.status !in 200..299) throw SignInFailed(SignInFailure.EXCHANGE_FAILED)
        val key = SignInHttp.parse(reply)?.string("key")?.takeIf(ApiKeys::acceptable) ?: throw SignInFailed(SignInFailure.EXCHANGE_FAILED)
        return SignInResult(Credential.ApiKey(key))
    }

    override fun toString(): String = "OpenRouterBrowserFlow"

    private companion object {
        const val PREFERRED_PORT = 3000
        const val PATH = "/openrouter-oauth/callback"
        const val CALLBACK_WAIT_MILLIS = 5 * 60_000L
    }
}
