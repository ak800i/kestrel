package dev.kestrel.providers.signin

import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.signin.SignInFixtures.fixture
import dev.kestrel.providers.signin.SignInFixtures.json
import dev.kestrel.providers.signin.SignInFixtures.read
import dev.kestrel.providers.testing.RedirectingCalls
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** xAI's device flow through OIDC discovery (design D9; research r31). */
class XaiDeviceFlowTest {
    private val server = MockWebServer()
    private lateinit var calls: RedirectingCalls
    private lateinit var http: ProviderHttp
    private var now = 1_000_000L
    private val pauses = mutableListOf<Long>()
    private val leases = mutableListOf<Set<String>>()
    private var open = 0

    @Before
    fun start() {
        server.start()
        calls = RedirectingCalls(server)
        http = ProviderHttp(calls)
    }

    @After
    fun stop() {
        server.close()
    }

    private fun flow() = XaiDeviceFlow(http, { hosts -> leases += hosts; open++; AutoCloseable { open-- } }, { now }) { pauses += it; now += it }

    private fun failureOf(block: suspend () -> Unit): SignInFailure =
        try {
            runBlocking { block() }
            fail("expected SignInFailed")
            throw AssertionError()
        } catch (e: SignInFailed) {
            e.kind
        }

    @Test
    fun theSignInLeasesTheDiscoveredHostsPrefersTheCompleteAddressAndKeepsTheTokenEndpoint() = runBlocking {
        server.enqueue(fixture(200, "xai/openid-configuration.json"))
        server.enqueue(fixture(200, "xai/device-authorization.json"))
        server.enqueue(json(400, """{"error":"authorization_pending"}"""))
        server.enqueue(json(400, """{"error":"slow_down"}"""))
        server.enqueue(fixture(200, "xai/token.json"))
        val flow = flow()

        val prompt = flow.start()
        val result = flow.await(prompt)

        assertEquals("https://accounts.x.ai/oauth2/device?user_code=ABCD-EFGH", prompt.verificationUrl)
        assertEquals(
            Credential.OAuthToken("test-xai-access-1", "test-xai-refresh-1", now + 3_600_000, tokenEndpoint = "https://auth.x.ai/oauth2/token"),
            result.credential,
        )
        assertEquals(setOf("auth.x.ai"), result.hosts)
        assertEquals(listOf(setOf("auth.x.ai")), leases)
        assertEquals(0, open)
        assertEquals(listOf(5_000L, 5_000L, 10_000L), pauses)
        assertEquals("https://auth.x.ai/.well-known/openid-configuration", calls.requests.first().toString())
    }

    @Test
    fun aDiscoveredEndpointOffXaiIsRefusedWithoutALease() {
        server.enqueue(json(200, read("xai/openid-configuration.json").replace("https://auth.x.ai/oauth2/token", "https://auth.example.com/oauth2/token")))

        assertEquals(SignInFailure.UNTRUSTED_ADDRESS, failureOf { flow().start() })
        assertEquals(emptyList<Set<String>>(), leases)
    }

    @Test
    fun aVerificationAddressOffXaiIsRefusedAndTheLeaseClosed() {
        server.enqueue(fixture(200, "xai/openid-configuration.json"))
        server.enqueue(json(200, read("xai/device-authorization.json").replace("https://accounts.x.ai/", "https://accounts.example.com/")))

        assertEquals(SignInFailure.UNTRUSTED_ADDRESS, failureOf { flow().start() })
        assertEquals(0, open)
    }

    @Test
    fun aTokenWithoutARefreshTokenOrAnExpiryFailsAndARefusalEndsTheSignIn() {
        server.enqueue(fixture(200, "xai/openid-configuration.json"))
        server.enqueue(fixture(200, "xai/device-authorization.json"))
        server.enqueue(json(200, """{"access_token":"test-xai-access-1","expires_in":3600}"""))
        server.enqueue(fixture(200, "xai/openid-configuration.json"))
        server.enqueue(fixture(200, "xai/device-authorization.json"))
        server.enqueue(json(200, """{"access_token":"test-xai-access-1","refresh_token":"test-xai-refresh-1"}"""))
        server.enqueue(fixture(200, "xai/openid-configuration.json"))
        server.enqueue(fixture(200, "xai/device-authorization.json"))
        server.enqueue(json(400, """{"error":"access_denied"}"""))
        server.enqueue(fixture(200, "xai/openid-configuration.json"))
        server.enqueue(fixture(200, "xai/device-authorization.json"))
        server.enqueue(json(400, """{"error":"expired_token"}"""))

        assertEquals(SignInFailure.EXCHANGE_FAILED, failureOf { flow().let { it.await(it.start()) } })
        assertEquals(SignInFailure.EXCHANGE_FAILED, failureOf { flow().let { it.await(it.start()) } })
        assertEquals(SignInFailure.REFUSED, failureOf { flow().let { it.await(it.start()) } })
        assertEquals(SignInFailure.EXPIRED, failureOf { flow().let { it.await(it.start()) } })
        assertEquals(0, open)
    }
}
