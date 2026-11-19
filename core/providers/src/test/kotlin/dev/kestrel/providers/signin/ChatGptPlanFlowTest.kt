package dev.kestrel.providers.signin

import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.profiles.ChatGptRegistration
import dev.kestrel.providers.signin.SignInFixtures.json
import dev.kestrel.providers.signin.SignInFixtures.read
import dev.kestrel.providers.testing.RedirectingCalls
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder

/**
 * The ChatGPT plan's sign-in and sign-out against MockWebServer (design D9 and D19; research r46; OpenAI's "Registration
 * and sign-in", "Accounts and sessions" and "Errors and recovery"). It has no live check (decision 4), so each case of
 * the design is here once.
 */
class ChatGptPlanFlowTest {
    private val server = MockWebServer()
    private lateinit var calls: RedirectingCalls
    private lateinit var http: ProviderHttp
    private val keys = TestIdTokens()
    private val loopback = FakeLoopback()
    private val now = TestIdTokens.NOW_SECONDS * 1000

    private var discovery = read("chatgpt-plan/openid-configuration.json")
    private val keySets = ArrayDeque<String>()
    private var tokenReply: (Map<String, String>) -> MockResponse = { json(200, tokenBody()) }
    private val revocations = ArrayDeque<MockResponse>()
    private val forms = mutableListOf<Map<String, String>>()

    private fun tokenBody(claims: String = keys.claims(nonce = loopback.page!!.queryParameter("nonce")!!)) =
        read("chatgpt-plan/token.json").replace("ID_TOKEN", keys.token(claims))

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val form = request.body?.utf8().orEmpty().split('&').filter { it.isNotEmpty() }.associate { field ->
                    val (name, value) = field.split('=', limit = 2)
                    name to URLDecoder.decode(value, Charsets.UTF_8)
                }
                return when (request.url.encodedPath) {
                    "/.well-known/openid-configuration" -> json(200, discovery)
                    "/.well-known/jwks.json" -> json(200, keySets.removeFirstOrNull() ?: keys.keySet())
                    "/api/accounts/oauth/token" -> tokenReply(form.also { forms += it })
                    "/oauth/revoke" -> revocations.removeFirst().also { forms += form }
                    else -> json(404, "{}")
                }
            }
        }
        server.start()
        calls = RedirectingCalls(server)
        http = ProviderHttp(calls)
    }

    @After
    fun stop() {
        server.close()
    }

    private fun flow(registration: ChatGptRegistration? = null, consent: Boolean = false) =
        ChatGptPlanFlow("urn:uuid:00000000-0000-4000-8000-000000000001", registration, consent, http) { now }

    private fun run(flow: ChatGptPlanFlow) = runBlocking { flow.run(loopback, loopback.open) }

    private fun failureOf(flow: ChatGptPlanFlow): SignInFailed =
        try {
            run(flow)
            fail("expected SignInFailed")
            throw AssertionError()
        } catch (e: SignInFailed) {
            e
        }

    private fun issuedCallback(clientId: String?): (okhttp3.HttpUrl) -> Map<String, String> = { page ->
        listOfNotNull("code" to "test-code", "state" to page.queryParameter("state")!!, clientId?.let { "client_id" to it }).toMap()
    }

    @Test
    fun theFirstRegistrationSendsTheDynamicClientTheNameAndTheHostIdAndKeepsTheIssuedClientId() {
        loopback.answer = issuedCallback("oaiapp_test")

        val result = run(flow())

        val page = loopback.page!!
        assertEquals("https://auth.openai.com/api/accounts/authorize", page.newBuilder().query(null).build().toString())
        assertEquals("dynamic_agent_client", page.queryParameter("client_id"))
        assertEquals("Kestrel", page.queryParameter("agent_name_hint"))
        assertEquals("urn:uuid:00000000-0000-4000-8000-000000000001", page.queryParameter("ext_agent_host_id"))
        assertEquals("code", page.queryParameter("response_type"))
        assertEquals("http://127.0.0.1:1455/auth/callback", page.queryParameter("redirect_uri"))
        assertEquals("openid profile email offline_access resource.invoke chatgpt.tokens.use.direct", page.queryParameter("scope"))
        assertEquals("https://api.openai.com/v1", page.queryParameter("resource"))
        assertEquals("S256", page.queryParameter("code_challenge_method"))
        assertNull(page.queryParameter("prompt"))
        assertNull(page.queryParameter("id_token_hint"))
        assertNull(page.queryParameter("login_hint"))
        assertEquals(listOf(Triple(LoopbackAddresses.IPV4, 1455 as Int?, "/auth/callback")), loopback.opened)
        assertEquals(1, loopback.closed)
        val exchange = forms.single()
        assertEquals("authorization_code", exchange["grant_type"])
        assertEquals("oaiapp_test", exchange["client_id"])
        assertEquals("test-code", exchange["code"])
        assertEquals("http://127.0.0.1:1455/auth/callback", exchange["redirect_uri"])
        assertEquals("https://api.openai.com/v1", exchange["resource"])
        assertEquals(page.queryParameter("code_challenge"), Pkce.challenge(exchange.getValue("code_verifier")))
        assertEquals(
            Credential.OAuthToken("test-access-1", "test-refresh-1", now + 3_600_000, 1_800_003_000_000L, clientId = "oaiapp_test"),
            result.credential,
        )
        assertEquals(ChatGptRegistration("oaiapp_test", "test-subject"), result.registration)
        assertEquals(setOf("api.openai.com", "auth.openai.com"), result.hosts)
    }

    @Test
    fun aBusyPort1455TakesAFreePortWithTheSamePath() {
        loopback.busy = setOf(1455)
        loopback.answer = issuedCallback("oaiapp_test")

        run(flow())

        assertEquals("http://127.0.0.1:54321/auth/callback", loopback.page!!.queryParameter("redirect_uri"))
        assertEquals("http://127.0.0.1:54321/auth/callback", forms.single()["redirect_uri"])
    }

    @Test
    fun aFirstCallbackWithoutAnIssuedClientIdOrWithTheDynamicOneIsAnIncompleteRegistration() {
        loopback.answer = issuedCallback(null)
        assertEquals(SignInFailure.REGISTRATION_INCOMPLETE, failureOf(flow()).kind)
        loopback.answer = issuedCallback("dynamic_agent_client")
        assertEquals(SignInFailure.REGISTRATION_INCOMPLETE, failureOf(flow()).kind)
        assertTrue(forms.isEmpty())
    }

    @Test
    fun aReturningCallbackMayOmitTheClientIdButNeverNameAnother() {
        val kept = ChatGptRegistration("oaiapp_test", "test-subject")
        loopback.answer = issuedCallback(null)

        val result = run(flow(kept))

        assertEquals("oaiapp_test", loopback.page!!.queryParameter("client_id"))
        assertNull(loopback.page!!.queryParameter("agent_name_hint"))
        assertEquals("oaiapp_test", (result.credential as Credential.OAuthToken).clientId)
        loopback.answer = issuedCallback("oaiapp_other")
        assertEquals(SignInFailure.OTHER_ACCOUNT, failureOf(flow(kept)).kind)
    }

    @Test
    fun accessDeniedIsACancelAndAnotherErrorOrARefusedClientRefusesTheRegistration() {
        loopback.answer = { mapOf("error" to "access_denied", "state" to it.queryParameter("state")!!) }
        assertEquals(SignInFailure.CANCELLED, failureOf(flow()).kind)
        loopback.answer = { mapOf("error" to "unauthorized_client", "state" to it.queryParameter("state")!!) }
        assertEquals(SignInFailure.REGISTRATION_REFUSED, failureOf(flow()).kind)
        loopback.answer = issuedCallback("oaiapp_test")
        tokenReply = { json(401, """{"error":"invalid_client"}""") }
        assertEquals(SignInFailure.REGISTRATION_REFUSED, failureOf(flow()).kind)
    }

    @Test
    fun invalidGrantAtTheExchangeKeepsTheIssuedClientIdForTryAgain() {
        loopback.answer = issuedCallback("oaiapp_test")
        tokenReply = { json(400, """{"error":"invalid_grant"}""") }

        val failed = failureOf(flow())

        assertEquals(SignInFailure.EXCHANGE_FAILED, failed.kind)
        assertEquals(ChatGptRegistration("oaiapp_test", null), failed.registration)
        // A 503 at the exchange keeps the issued client ID too, so that "Try again" never registers Kestrel again.
        tokenReply = { json(503, "{}") }
        val unavailable = failureOf(flow())
        assertEquals(SignInFailure.EXCHANGE_FAILED, unavailable.kind)
        assertEquals(ChatGptRegistration("oaiapp_test", null), unavailable.registration)
    }

    @Test
    fun aDiscoveryDocumentWithAForeignKeySetOrRevocationAddressIsRefused() {
        val original = discovery
        discovery = original.replace("https://auth.openai.com/.well-known/jwks.json", "https://keys.example.com/jwks.json")
        assertEquals(SignInFailure.UNTRUSTED_ADDRESS, failureOf(flow()).kind)
        discovery = original.replace("https://auth.openai.com/oauth/revoke", "http://auth.openai.com/oauth/revoke")
        assertEquals(SignInFailure.UNTRUSTED_ADDRESS, failureOf(flow()).kind)
        assertNull(loopback.page)
    }

    @Test
    fun aMissingPlanScopeKeepsTheRegistrationAndConsentAsksForItAgain() {
        loopback.answer = issuedCallback("oaiapp_test")
        tokenReply = { json(200, tokenBody().replace("chatgpt.tokens.use.direct ", "")) }

        val failed = failureOf(flow())
        assertEquals(SignInFailure.PLAN_USE_NOT_ALLOWED, failed.kind)
        assertEquals(ChatGptRegistration("oaiapp_test", "test-subject"), failed.registration)

        tokenReply = { json(200, tokenBody()) }
        loopback.answer = issuedCallback(null)
        run(flow(failed.registration, consent = true))
        assertEquals("consent", loopback.page!!.queryParameter("prompt"))
    }

    @Test
    fun anotherAccountsSubjectReplacesNothing() {
        loopback.answer = issuedCallback(null)
        tokenReply = { json(200, tokenBody(keys.claims(nonce = loopback.page!!.queryParameter("nonce")!!, subject = "another-subject"))) }

        val failed = failureOf(flow(ChatGptRegistration("oaiapp_test", "test-subject")))

        assertEquals(SignInFailure.OTHER_ACCOUNT, failed.kind)
        assertNull(failed.registration)
    }

    @Test
    fun anUnknownKidIsFetchedOnceMoreAndAKidStillUnknownFails() {
        loopback.answer = issuedCallback("oaiapp_test")
        keySets += keys.keySet(withKey = false)
        run(flow())

        keySets += keys.keySet(withKey = false)
        keySets += keys.keySet(withKey = false)
        val failed = failureOf(flow())

        assertEquals(SignInFailure.EXCHANGE_FAILED, failed.kind)
        assertEquals(4, calls.requests.count { it.encodedPath == "/.well-known/jwks.json" })
    }

    @Test
    fun theSignOutRevokesTheRefreshTokenWithTwoRetriesAndSaysWhenOpenAiDidNotConfirm() = runBlocking {
        val token = Credential.OAuthToken("test-access-1", "test-refresh-1", null, clientId = "oaiapp_test")
        val pauses = mutableListOf<Long>()
        revocations += json(503, "{}")
        revocations += json(502, "{}")
        revocations += MockResponse.Builder().code(200).build()

        assertTrue(ChatGptPlanFlow.revoke(http, token) { pauses += it })
        assertEquals(listOf(1_000L, 3_000L), pauses)
        assertEquals(mapOf("token" to "test-refresh-1", "token_type_hint" to "refresh_token", "client_id" to "oaiapp_test"), forms.last())

        repeat(3) { revocations += json(503, "{}") }
        assertFalse(ChatGptPlanFlow.revoke(http, token) {})
    }
}
