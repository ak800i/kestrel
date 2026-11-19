package dev.kestrel.providers.discovery

import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.http.ApiKeyAuth
import dev.kestrel.providers.http.KeyHeader
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.signin.SignInFixtures.fixture
import dev.kestrel.providers.signin.SignInFixtures.json
import dev.kestrel.providers.testing.RedirectingCalls
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The ChatGPT plan's model list (design D13 and D17; research r46). */
class ChatGptPlanModelsTest {
    private val server = MockWebServer()
    private lateinit var calls: RedirectingCalls
    private lateinit var http: ProviderHttp
    private val auth = ApiKeyAuth("test-access-1", KeyHeader.BEARER)

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
    fun onlyTheListedRowsAreShownEachWithToolSupportOnResponses() = runBlocking {
        server.enqueue(fixture(200, "chatgpt-plan/models.json"))

        val models = ChatGptPlanModels(http, auth).list()

        assertEquals(listOf("gpt-6.1-sol" to "GPT-6.1 Sol", "gpt-6-luna" to "GPT-6 Luna"), models.map { it.id to it.name })
        assertEquals(listOf(true, true), models.map { it.tools })
        assertEquals(setOf(RequestStyle.RESPONSES), models.map { it.style }.toSet())
        assertEquals("https://api.openai.com/v1/models", calls.requests.single().toString())
        assertEquals("Bearer test-access-1", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun anAccountWithoutAPlanGetsItsOwnCode() {
        server.enqueue(json(403, """{"error":{"code":"subscription_sharing_user_not_eligible","message":"x"}}"""))

        try {
            runBlocking { ChatGptPlanModels(http, auth).list() }
            fail("expected a ProviderException")
        } catch (e: ProviderException) {
            assertEquals(ProviderError.QuotaOrPolicy(403, "subscription_sharing_user_not_eligible"), e.error)
        }
    }
}
