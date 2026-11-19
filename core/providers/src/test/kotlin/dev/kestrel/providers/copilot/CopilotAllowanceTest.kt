package dev.kestrel.providers.copilot

import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.signin.SignInFixtures.fixture
import dev.kestrel.providers.signin.SignInFixtures.json
import dev.kestrel.providers.testing.RedirectingCalls
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** "Check allowance" (design D12; research r27 §6 and §8). */
class CopilotAllowanceTest {
    private val server = MockWebServer()
    private lateinit var http: ProviderHttp
    private val token = Credential.GitHubToken("test-github-token", "github.com")

    @Before
    fun start() {
        server.start()
        http = ProviderHttp(RedirectingCalls(server))
    }

    @After
    fun stop() {
        server.close()
    }

    @Test
    fun theAllowanceSendsTheIdeHeadersWithTheTokenSchemeAndKeepsOnlyNumbers() = runBlocking {
        server.enqueue(fixture(200, "copilot/user.json"))
        val ide = mapOf("Accept-Encoding" to "identity", "Editor-Version" to "vscode/1.107.0", "Editor-Plugin-Version" to "copilot-chat/0.35.0", "User-Agent" to "GitHubCopilotChat/0.35.0")

        val quotas = CopilotAllowance(http, ide).check(token)

        assertEquals(listOf(CopilotQuota("premium", 70.0), CopilotQuota("chat", 100.0)), quotas)
        val sent = server.takeRequest()
        assertEquals("token test-github-token", sent.headers["Authorization"])
        assertEquals("2025-04-01", sent.headers["X-Github-Api-Version"])
        assertEquals("vscode/1.107.0", sent.headers["Editor-Version"])
        assertEquals("GitHubCopilotChat/0.35.0", sent.headers["User-Agent"])
        assertNull(sent.headers["Copilot-Integration-Id"])
        server.enqueue(json(200, """{"quota_snapshots":{}}"""))
        assertEquals(emptyList<CopilotQuota>(), CopilotAllowance(http, ide).check(token))
    }
}
