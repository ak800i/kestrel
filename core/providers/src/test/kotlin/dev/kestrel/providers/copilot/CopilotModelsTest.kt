package dev.kestrel.providers.copilot

import dev.kestrel.providers.catalog.InputKind
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.discovery.DiscoveredModel
import dev.kestrel.providers.http.ApiKeyAuth
import dev.kestrel.providers.http.KeyHeader
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.signin.SignInFixtures.catalog
import dev.kestrel.providers.signin.SignInFixtures.fixture
import dev.kestrel.providers.testing.RedirectingCalls
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Copilot's model list, its filters and the style per model (design D12; research r27 §5 and §7). */
class CopilotModelsTest {
    private val server = MockWebServer()
    private lateinit var calls: RedirectingCalls
    private lateinit var http: ProviderHttp

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
    fun theListKeepsTheVisibleChatModelsThatStreamWithTheirStylesAndCapabilities() = runBlocking {
        server.enqueue(fixture(200, "copilot/models.json"))
        val runtime = catalog.overrides.copilot.runtimeHeaders

        val models = CopilotModels(http, "https://api.individual.githubcopilot.com/".toHttpUrl(), ApiKeyAuth("test-github-token", KeyHeader.BEARER), runtime).list()

        assertEquals(listOf("gpt-6-luna", "gpt-6.1-terra", "claude-haiku-5.5", "gemini-3.8-flash", "gpt-5.4-nano"), models.map { it.id })
        assertEquals(
            DiscoveredModel(
                "gpt-6-luna", "GPT-6 Luna", setOf(InputKind.TEXT, InputKind.IMAGE), reasoning = true, reasoningEfforts = listOf("low", "medium", "high"),
                contextWindow = 400_000, maxTokens = 128_000, tools = true, streaming = true, style = RequestStyle.RESPONSES,
            ),
            models[0],
        )
        assertEquals(RequestStyle.RESPONSES, models[1].style)
        assertEquals(RequestStyle.ANTHROPIC, models[2].style)
        assertEquals(RequestStyle.CHAT_COMPLETIONS, models[3].style)
        assertEquals(false, models[4].tools)
        assertEquals("https://api.individual.githubcopilot.com/models", calls.requests.single().toString())
        val sent = server.takeRequest()
        for ((name, value) in runtime) assertEquals(name, value, sent.headers[name])
        assertEquals("Bearer test-github-token", sent.headers["Authorization"])
    }

    @Test
    fun theStyleIsThePreferredOneOrTheFirstThatTheModelLists() {
        assertEquals(RequestStyle.ANTHROPIC, CopilotModels.styleFor("claude-sonnet-5.5", null, emptyList()))
        assertEquals(RequestStyle.ANTHROPIC, CopilotModels.styleFor("sonnet", "anthropic", listOf("/v1/messages/")))
        assertEquals(RequestStyle.RESPONSES, CopilotModels.styleFor("claude-x", null, listOf("/responses")))
        assertEquals(RequestStyle.CHAT_COMPLETIONS, CopilotModels.styleFor("gemini-4-argon", null, listOf("/chat/completions", "/responses")))
        assertEquals(RequestStyle.CHAT_COMPLETIONS, CopilotModels.styleFor("gpt-x", null, listOf("/chat/completions")))
        assertEquals(RequestStyle.RESPONSES, CopilotModels.styleFor("gpt-x", null, listOf("/embeddings")))
    }
}
