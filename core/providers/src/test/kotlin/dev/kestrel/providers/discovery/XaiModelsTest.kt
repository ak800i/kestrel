package dev.kestrel.providers.discovery

import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.http.ApiKeyAuth
import dev.kestrel.providers.http.KeyHeader
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.signin.SignInFixtures.fixture
import dev.kestrel.providers.testing.RedirectingCalls
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** The model list of xAI's sign-in on the Grok CLI proxy (design D13; research r31). */
class XaiModelsTest {
    private val server = MockWebServer()
    private lateinit var calls: RedirectingCalls

    @Before
    fun start() {
        server.start()
        calls = RedirectingCalls(server)
    }

    @After
    fun stop() {
        server.close()
    }

    @Test
    fun everyChatRowGoesThroughResponsesAndOtherBackendsAreDropped() = runBlocking {
        server.enqueue(fixture(200, "xai/models.json"))
        val identity = mapOf("X-XAI-Token-Auth" to "xai-grok-cli", "x-grok-client-version" to "1.0.13")

        val models = XaiModels(ProviderHttp(calls), ApiKeyAuth("test-xai-access-1", KeyHeader.BEARER), identity).list()

        // As OpenClaw's buildXaiOauthModelFromLiveRow: the proxy serves every row through Responses, and an image model is dropped.
        assertEquals(
            listOf("grok-4.7" to RequestStyle.RESPONSES, "grok-code-2" to RequestStyle.RESPONSES, "grok-4.7-mini" to RequestStyle.RESPONSES),
            models.map { it.id to it.style },
        )
        assertEquals(listOf("low", "high"), models[0].reasoningEfforts)
        assertEquals(listOf("low", "medium"), models[2].reasoningEfforts)
        assertEquals(2_000_000, models[0].contextWindow)
        assertEquals(64_000, models[0].maxTokens)
        assertEquals(false, models[1].reasoning)
        assertEquals("https://cli-chat-proxy.grok.com/v1/models", calls.requests.single().toString())
        assertEquals("xai-grok-cli", server.takeRequest().headers["X-XAI-Token-Auth"])
    }
}
