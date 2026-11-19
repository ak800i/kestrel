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
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Copilot's device flow on github.com and a GHE.com tenant (design D9 and D12; research r27). */
class GitHubDeviceFlowTest {
    private val server = MockWebServer()
    private lateinit var calls: RedirectingCalls
    private lateinit var http: ProviderHttp
    private var now = 1_000_000L
    private val pauses = mutableListOf<Long>()

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

    private fun flow(domain: String = "github.com") = GitHubDeviceFlow(domain, http, { now }) { pauses += it; now += it }

    private fun failureOf(block: suspend () -> Unit): SignInFailure =
        try {
            runBlocking { block() }
            fail("expected SignInFailed")
            throw AssertionError()
        } catch (e: SignInFailed) {
            e.kind
        }

    @Test
    fun aSignInPollsAfterEachIntervalAndSlowsDownWhenAsked() = runBlocking {
        server.enqueue(fixture(200, "github/device-code.json"))
        server.enqueue(json(200, """{"error":"authorization_pending"}"""))
        server.enqueue(json(200, """{"error":"slow_down","interval":12}"""))
        server.enqueue(json(200, """{"error":"authorization_pending"}"""))
        server.enqueue(fixture(200, "github/token.json"))
        val flow = flow()

        val prompt = flow.start()
        val result = flow.await(prompt)

        assertEquals(DeviceCodePrompt("WDJB-MJHT", "https://github.com/login/device", 1_000_000L + 900_000, 5_000), prompt)
        assertEquals(Credential.GitHubToken("test-github-token", "github.com"), result.credential)
        assertEquals(setOf("api.github.com"), result.hosts)
        assertEquals(listOf(5_000L, 5_000L, 12_000L, 12_000L), pauses)
        assertEquals("https://github.com/login/device/code", calls.requests.first().toString())
        assertEquals(4, calls.requests.count { it.toString() == "https://github.com/login/oauth/access_token" })
        assertEquals("client_id=Iv1.b507a08c87ecfe98&scope=read%3Auser", server.takeRequest().body!!.utf8())
        assertEquals(
            "client_id=Iv1.b507a08c87ecfe98&device_code=test-device-code&grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code",
            server.takeRequest().body!!.utf8(),
        )
    }

    @Test
    fun aTenantSignsInOnItsOwnDomain() = runBlocking {
        server.enqueue(json(200, read("github/device-code.json").replace("https://github.com/", "https://acme.ghe.com/")))
        server.enqueue(fixture(200, "github/token.json"))
        val flow = flow("acme.ghe.com")

        val result = flow.await(flow.start())

        assertEquals(Credential.GitHubToken("test-github-token", "acme.ghe.com"), result.credential)
        assertEquals(setOf("api.acme.ghe.com"), result.hosts)
        assertEquals(setOf("acme.ghe.com"), flow.signInHosts)
        assertEquals("https://acme.ghe.com/login/device/code", calls.requests.first().toString())
        assertEquals("acme.ghe.com", GitHubDeviceFlow.tenant("acme"))
        assertEquals("acme.ghe.com", GitHubDeviceFlow.tenant(" https://Acme.ghe.com/ "))
        assertNull(GitHubDeviceFlow.tenant("acme.example.com"))
        try {
            flow("api.example.com")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("Not github.com or a GHE.com tenant", e.message)
        }
    }

    @Test
    fun aVerificationAddressOnAnotherHostIsRefusedAndTheIntervalHasAFloor() {
        server.enqueue(json(200, read("github/device-code.json").replace("https://github.com/login/device", "https://github.example.com/login/device")))
        server.enqueue(json(200, read("github/device-code.json").replace("\"interval\": 5", "\"interval\": 0")))

        assertEquals(SignInFailure.UNTRUSTED_ADDRESS, failureOf { flow().start() })
        // The polls are at least 1 s apart.
        assertEquals(1_000L, runBlocking { flow().start() }.intervalMillis)
    }

    @Test
    fun refusalExpiryAndTheDeadlineEndTheSignIn() {
        server.enqueue(fixture(200, "github/device-code.json"))
        server.enqueue(json(200, """{"error":"access_denied"}"""))
        server.enqueue(fixture(200, "github/device-code.json"))
        server.enqueue(json(200, """{"error":"expired_token"}"""))
        server.enqueue(json(200, read("github/device-code.json").replace("\"expires_in\": 900", "\"expires_in\": 4")))

        assertEquals(SignInFailure.REFUSED, failureOf { flow().let { it.await(it.start()) } })
        assertEquals(SignInFailure.EXPIRED, failureOf { flow().let { it.await(it.start()) } })
        assertEquals(SignInFailure.EXPIRED, failureOf { flow().let { it.await(it.start()) } })
        // The last code expired before its first poll, so no poll was sent for it.
        assertEquals(5, calls.requests.size)
    }
}
