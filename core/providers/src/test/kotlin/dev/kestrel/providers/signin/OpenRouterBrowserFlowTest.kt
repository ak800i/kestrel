package dev.kestrel.providers.signin

import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.signin.SignInFixtures.json
import dev.kestrel.providers.testing.RedirectingCalls
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** OpenRouter's PKCE sign-in with a `localhost` redirect (design D9 and D10; research r32). */
class OpenRouterBrowserFlowTest {
    private val server = MockWebServer()
    private lateinit var calls: RedirectingCalls
    private lateinit var http: ProviderHttp
    private val loopback = FakeLoopback()

    @Before
    fun start() {
        server.start()
        calls = RedirectingCalls(server)
        http = ProviderHttp(calls)
        // OpenRouter puts the state into the callback address that it was given.
        loopback.answer = { page -> mapOf("code" to "test-code", "state" to page.queryParameter("callback_url")!!.toHttpUrl().queryParameter("state")!!) }
    }

    @After
    fun stop() {
        server.close()
    }

    private fun failureOf(block: suspend () -> Unit): SignInFailure =
        try {
            runBlocking { block() }
            fail("expected SignInFailed")
            throw AssertionError()
        } catch (e: SignInFailed) {
            e.kind
        }

    @Test
    fun theSignInListensOnBothLoopbackAddressesOnPort3000AndExchangesTheCodeForAKey() = runBlocking {
        server.enqueue(json(200, """{"key":"sk-or-test-key","user_id":"test-user"}"""))

        val result = OpenRouterBrowserFlow(http).run(loopback, loopback.open)

        val page = loopback.page!!
        assertEquals("https://openrouter.ai/auth", page.newBuilder().query(null).build().toString())
        val callback = page.queryParameter("callback_url")!!.toHttpUrl()
        assertEquals("http://localhost:3000/openrouter-oauth/callback", callback.newBuilder().query(null).build().toString())
        assertEquals("S256", page.queryParameter("code_challenge_method"))
        assertEquals(listOf(Triple(LoopbackAddresses.BOTH, 3000 as Int?, "/openrouter-oauth/callback")), loopback.opened)
        assertEquals(Credential.ApiKey("sk-or-test-key"), result.credential)
        assertEquals("https://openrouter.ai/api/v1/auth/keys", calls.requests.single().toString())
        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()) as JsonObject
        assertEquals("test-code", body["code"]!!.jsonPrimitive.content)
        assertEquals("S256", body["code_challenge_method"]!!.jsonPrimitive.content)
        assertEquals(page.queryParameter("code_challenge"), Pkce.challenge(body["code_verifier"]!!.jsonPrimitive.content))
    }

    @Test
    fun aBusyPort3000TakesAFreePort() = runBlocking {
        loopback.busy = setOf(3000)
        server.enqueue(json(200, """{"key":"sk-or-test-key"}"""))

        OpenRouterBrowserFlow(http).run(loopback, loopback.open)

        assertEquals("localhost:54321", loopback.page!!.queryParameter("callback_url")!!.toHttpUrl().let { "${it.host}:${it.port}" })
    }

    @Test
    fun aRefusedCodeOrNoCallbackEndsTheSignIn() {
        server.enqueue(json(403, """{"error":{"message":"invalid code"}}"""))
        assertEquals(SignInFailure.EXCHANGE_FAILED, failureOf { OpenRouterBrowserFlow(http).run(loopback, loopback.open) })

        loopback.answer = { null }
        assertEquals(SignInFailure.NO_CALLBACK, failureOf { OpenRouterBrowserFlow(http).run(loopback, loopback.open) })
    }
}
