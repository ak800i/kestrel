package dev.kestrel.providers.copilot

import dev.kestrel.providers.catalog.AuthMethod
import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.credentials.CredentialManager
import dev.kestrel.providers.credentials.CredentialVault
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.profiles.ModelRole
import dev.kestrel.providers.profiles.Profile
import dev.kestrel.providers.profiles.ProfileModel
import dev.kestrel.providers.signin.SignInFixtures.catalog
import dev.kestrel.providers.signin.SignInFixtures.fixture
import dev.kestrel.providers.signin.SignInFixtures.json
import dev.kestrel.providers.signin.SignInFixtures.read
import dev.kestrel.providers.testing.RedirectingCalls
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Copilot's targets: the route per model, the discovered base and its update (design D12). */
class CopilotTargetsTest {
    private val server = MockWebServer()
    private lateinit var calls: RedirectingCalls
    private lateinit var http: ProviderHttp
    private lateinit var credentials: CredentialManager
    private val updates = mutableListOf<HttpUrl>()
    private val request = ChatRequest("", listOf(ChatMessage.User(listOf(Part.Text("Hi")))), maxOutputTokens = 16)

    @Before
    fun start() {
        server.start()
        calls = RedirectingCalls(server)
        http = ProviderHttp(calls)
        val vault = object : CredentialVault {
            override suspend fun read(id: String): Credential? = Credential.GitHubToken("test-github-token", "github.com")

            override suspend fun write(id: String, credential: Credential) = Unit

            override suspend fun delete(id: String) = Unit
        }
        credentials = CredentialManager(vault, http, catalog, CoroutineScope(Dispatchers.Default))
    }

    @After
    fun stop() {
        server.close()
    }

    private fun profile(base: String?) = Profile(
        "test-profile", "Test", "github-copilot", "github-copilot", AuthMethod.DEVICE_CODE, "test-credential", baseUrl = base,
        lightweight = ProfileModel("claude-haiku-5.5"), versatile = ProfileModel("gemini-3.8-flash"),
    )

    private fun targets() = CopilotTargets(catalog, credentials, CopilotDiscovery(http)) { _, base -> updates += base }

    @Test
    fun eachRouteGetsItsPathOptionsHeadersAndTheBearer() = runBlocking {
        server.enqueue(fixture(200, "copilot/user.json"))
        val targets = targets()
        val profile = profile("https://api.individual.githubcopilot.com/")

        val claude = targets.target(profile, ModelRole.LIGHTWEIGHT, ModelEntry("claude-haiku-5.5", "Claude Haiku 5.5", style = RequestStyle.ANTHROPIC))
        val gemini = targets.target(profile, ModelRole.VERSATILE, ModelEntry("gemini-3.8-flash", "Gemini 3.8 Flash", style = RequestStyle.CHAT_COMPLETIONS))

        assertEquals(RequestStyle.ANTHROPIC, claude.style)
        assertEquals("/messages", claude.options.messagesPath)
        assertTrue(claude.options.stripThinking && claude.options.sanitizeToolCallIds && claude.options.stream == true)
        assertEquals(RequestStyle.CHAT_COMPLETIONS, gemini.style)
        assertEquals(CopilotTargets.COMPAT, gemini.options.compat)
        assertEquals("https://api.individual.githubcopilot.com/", gemini.baseUrl.toString())
        assertEquals("user", claude.headers.headers(request)["x-initiator"])
        assertEquals("copilot-developer-cli", claude.headers.headers(request)["Copilot-Integration-Id"])
        assertEquals(mapOf("Authorization" to "Bearer test-github-token"), claude.auth.headers(HttpRequest("POST", "https://example.com/".toHttpUrl())))
        assertEquals(emptyList<HttpUrl>(), updates)
        assertEquals(1, calls.requests.size)

        // An address that Kestrel does not trust fails the request as an answer that Kestrel cannot read.
        server.enqueue(json(200, read("copilot/user.json").replace("https://api.individual.githubcopilot.com", "https://copilot.example.com")))
        try {
            targets().target(profile, ModelRole.LIGHTWEIGHT, ModelEntry("gpt-6-luna", "GPT-6 Luna"))
            fail("expected a ProviderException")
        } catch (e: ProviderException) {
            assertEquals(ProviderError.Malformed, e.error)
        }
    }

    @Test
    fun aChangedBaseIsStoredOnceAndLaterRequestsInTheProcessUseIt() = runBlocking {
        server.enqueue(fixture(200, "copilot/user.json"))
        val targets = targets()
        val profile = profile("https://api.business.githubcopilot.com/")

        val first = targets.target(profile, ModelRole.LIGHTWEIGHT, ModelEntry("gpt-6-luna", "GPT-6 Luna", style = RequestStyle.RESPONSES))
        // A client that sub-project 5 kept still holds the profile with the old base.
        val later = targets.target(profile, ModelRole.LIGHTWEIGHT, ModelEntry("gpt-6-luna", "GPT-6 Luna"))

        assertEquals("https://api.individual.githubcopilot.com/", first.baseUrl.toString())
        assertEquals(listOf("https://api.individual.githubcopilot.com/".toHttpUrl()), updates)
        assertEquals(first.baseUrl, later.baseUrl)
        assertEquals(RequestStyle.RESPONSES, later.style)
        assertEquals(1, calls.requests.size)
    }

    @Test
    fun twoRequestsThatStartTogetherRunDiscoveryOnce() = runBlocking {
        server.enqueue(fixture(200, "copilot/user.json"))
        server.enqueue(fixture(200, "copilot/user.json"))
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val targets = CopilotTargets(catalog, credentials, CopilotDiscovery(http)) { _, base ->
            updates += base
            entered.complete(Unit)
            gate.await()
        }
        val profile = profile("https://api.business.githubcopilot.com/")
        val entry = ModelEntry("gpt-6-luna", "GPT-6 Luna")

        val first = async { targets.target(profile, ModelRole.LIGHTWEIGHT, entry) }
        entered.await()
        // The second request starts while the first one waits for the allowlist.
        val second = async { targets.target(profile, ModelRole.VERSATILE, entry) }
        yield()
        gate.complete(Unit)

        assertEquals(first.await().baseUrl, second.await().baseUrl)
        assertEquals(listOf("https://api.individual.githubcopilot.com/".toHttpUrl()), updates)
        assertEquals(1, calls.requests.size)
    }

    @Test
    fun withoutNetworkTheStoredBaseIsUsedForThisProcess() = runBlocking {
        server.close()

        val target = targets().target(profile("https://api.business.githubcopilot.com/"), ModelRole.LIGHTWEIGHT, ModelEntry("gpt-6-luna", "GPT-6 Luna"))

        assertEquals("https://api.business.githubcopilot.com/", target.baseUrl.toString())
        assertEquals(emptyList<HttpUrl>(), updates)
    }
}
