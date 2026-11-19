package dev.kestrel.providers.copilot

import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.signin.SignInFailed
import dev.kestrel.providers.signin.SignInFailure
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

/** Copilot's discovery of its API address (design D12; research r27 §4). */
class CopilotDiscoveryTest {
    private val server = MockWebServer()
    private lateinit var calls: RedirectingCalls
    private lateinit var http: ProviderHttp
    private val token = Credential.GitHubToken("test-github-token", "github.com")

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

    @Test
    fun theApiAddressComesFromTheUserCallWithOnlyAcceptAndTheBearer() = runBlocking {
        server.enqueue(fixture(200, "copilot/user.json"))

        val base = CopilotDiscovery(http).apiBase(token)

        assertEquals("https://api.individual.githubcopilot.com/", base.toString())
        assertEquals("https://api.github.com/copilot_internal/user", calls.requests.single().toString())
        val sent = server.takeRequest()
        assertEquals("Bearer test-github-token", sent.headers["Authorization"])
        assertEquals("application/json", sent.headers["Accept"])
        assertNull(sent.headers["Editor-Version"])
        assertNull(sent.headers["Copilot-Integration-Id"])
    }

    @Test
    fun aMissingAddressGivesTheFallbackAndAnUntrustedOneIsRefused() = runBlocking {
        server.enqueue(json(200, """{"endpoints":{"api":""}}"""))
        server.enqueue(json(200, """{"login":"x"}"""))
        server.enqueue(json(200, read("copilot/user.json").replace("https://api.individual.githubcopilot.com", "https://copilot.example.com")))

        assertEquals("https://copilot-api.acme.ghe.com/", CopilotDiscovery(http).apiBase(token.copy(domain = "acme.ghe.com")).toString())
        assertEquals("https://api.individual.githubcopilot.com/", CopilotDiscovery(http).apiBase(token).toString())
        try {
            CopilotDiscovery(http).apiBase(token)
            fail("expected SignInFailed")
        } catch (e: SignInFailed) {
            assertEquals(SignInFailure.UNTRUSTED_ADDRESS, e.kind)
        }
        assertEquals("https://api.acme.ghe.com/copilot_internal/user", calls.requests.first().toString())
    }
}
