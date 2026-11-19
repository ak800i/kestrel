package dev.kestrel.providers.client

import dev.kestrel.providers.catalog.AuthMethod
import dev.kestrel.providers.catalog.InputKind
import dev.kestrel.providers.catalog.LocalServerKind
import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.catalog.ToolMode
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.ModelTarget
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.credentials.CredentialManager
import dev.kestrel.providers.credentials.CredentialVault
import dev.kestrel.providers.discovery.DiscoveredModel
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.NoAuth
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.profiles.ModelRole
import dev.kestrel.providers.profiles.Profile
import dev.kestrel.providers.profiles.ProfileModel
import dev.kestrel.providers.signin.SignInFixtures.catalog
import dev.kestrel.providers.styles.AzureRouting
import dev.kestrel.providers.styles.MantleRouting
import dev.kestrel.providers.styles.VertexUrls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** TargetResolver's base URLs, key headers, options and headers per choice (design D4, D9 and D13). */
class TargetResolverTest {
    private val vault = object : CredentialVault {
        val stored = mutableMapOf<String, Credential>("test-credential" to Credential.ApiKey("test-key"))

        override suspend fun read(id: String): Credential? = stored[id]

        override suspend fun write(id: String, credential: Credential) {
            stored[id] = credential
        }

        override suspend fun delete(id: String) {
            stored.remove(id)
        }
    }
    private val http = ProviderHttp(Call.Factory { error("no request in these tests") })
    private val credentials = CredentialManager(vault, http, catalog, CoroutineScope(Dispatchers.Default))
    private val request = ChatRequest("", listOf(ChatMessage.User(listOf(Part.Text("Hi")))), maxOutputTokens = 16)
    private val probe = HttpRequest("POST", "https://example.com/".toHttpUrl())

    private fun profile(
        provider: String,
        choice: String,
        method: AuthMethod = AuthMethod.API_KEY,
        model: ProfileModel = ProfileModel("test-model"),
        base: String? = null,
        region: String? = null,
        server: LocalServerKind? = null,
        credential: String = "test-credential",
    ) = Profile("test-profile", "Test", provider, choice, method, credential, base, region, server, model, model)

    private fun resolve(profile: Profile, discovered: List<DiscoveredModel> = emptyList(), special: Map<String, ProviderTargets> = emptyMap()) =
        runBlocking { TargetResolver(catalog, credentials, { discovered }, special).resolve(profile, ModelRole.LIGHTWEIGHT) }

    private fun headersOf(target: ModelTarget) = target.headers.headers(request)

    private fun authOf(target: ModelTarget) = runBlocking { target.auth.headers(probe) }

    @Test
    fun anXaiKeyRequestCarriesNoneOfTheGrokCliHeaders() {
        val target = resolve(profile("xai", "xai-api-key", model = ProfileModel("grok-4.7")))

        assertEquals("https://api.x.ai/v1", target.baseUrl.toString())
        assertEquals(RequestStyle.RESPONSES, target.style)
        assertTrue(headersOf(target).keys.none { it.lowercase() in setOf("x-xai-token-auth", "x-grok-client-version", "x-grok-model-override") })
        assertEquals(mapOf("Authorization" to "Bearer test-key"), authOf(target))
    }

    @Test
    fun anXaiSignInGoesToTheProxyWithItsHeadersAndTheModelOverride() {
        vault.stored["test-credential"] = Credential.OAuthToken("test-access", "test-refresh", null, tokenEndpoint = "https://auth.x.ai/oauth2/token")

        val target = resolve(profile("xai", "xai-oauth", AuthMethod.DEVICE_CODE, ProfileModel("grok-4.7")))

        assertEquals("https://cli-chat-proxy.grok.com/v1", target.baseUrl.toString())
        assertEquals(
            mapOf("X-XAI-Token-Auth" to "xai-grok-cli", "x-grok-client-version" to "1.0.13", "x-grok-model-override" to "grok-4.7"),
            headersOf(target),
        )
        assertEquals(mapOf("Authorization" to "Bearer test-access"), authOf(target))
    }

    @Test
    fun theChatGptPlanUsesResponsesAtTheApiWithItsPreviewOption() {
        val plain = resolve(profile("openai", "openai-api-key", model = ProfileModel("gpt-6-luna")))
        vault.stored["test-plan-credential"] = Credential.OAuthToken("test-access", "test-refresh", null, clientId = "oaiapp_test")
        val plan = resolve(profile("openai", "chatgpt-plan", AuthMethod.BROWSER, ProfileModel("gpt-6-luna"), credential = "test-plan-credential"))

        assertEquals("https://api.openai.com/v1", plan.baseUrl.toString())
        assertEquals(RequestStyle.RESPONSES, plan.style)
        assertTrue(plan.options.chatgptPlan)
        assertFalse(plain.options.chatgptPlan)
        assertEquals(mapOf("Authorization" to "Bearer test-access"), authOf(plan))
    }

    @Test
    fun eachStyleGetsItsDefaultKeyHeaderUnlessTheChoiceNamesOne() {
        val anthropic = resolve(profile("anthropic", "apiKey", model = ProfileModel("claude-test")))
        val miniMax = resolve(profile("minimax", "minimax-global-api", model = ProfileModel("MiniMax-M3")))
        val gemini = resolve(profile("google", "gemini-api-key", model = ProfileModel("gemini-test")))

        assertEquals(mapOf("x-api-key" to "test-key"), authOf(anthropic))
        assertEquals(RequestStyle.ANTHROPIC, miniMax.style)
        assertEquals("https://api.minimax.io/anthropic", miniMax.baseUrl.toString())
        assertEquals(mapOf("Authorization" to "Bearer test-key"), authOf(miniMax))
        assertEquals(mapOf("x-goog-api-key" to "test-key"), authOf(gemini))
    }

    @Test
    fun mantleAndAzureFollowTheirRouting() {
        val mantle = resolve(profile("amazon-bedrock-mantle", "mantle-api-key", model = ProfileModel("anthropic.claude-sonnet-5"), region = "us-east-1"))
        val azure = resolve(profile("microsoft-foundry", "microsoft-foundry-apikey", model = ProfileModel("gpt-6-luna"), base = "https://test.openai.azure.com/openai/v1"))
        val chosen = resolve(
            profile("microsoft-foundry", "microsoft-foundry-apikey", model = ProfileModel("my-model", style = RequestStyle.ANTHROPIC), base = "https://test.services.ai.azure.com"),
        )

        val route = MantleRouting.route("anthropic.claude-sonnet-5", "us-east-1")
        assertEquals(route.style, mantle.style)
        assertEquals(route.baseUrl, mantle.baseUrl)
        assertEquals(RequestStyle.AZURE, azure.style)
        assertEquals(AzureRouting.normalise("https://test.openai.azure.com/openai/v1"), azure.baseUrl)
        assertEquals(AzureRouting.styleFor("gpt-6-luna"), azure.options.wire)
        assertEquals(mapOf("api-key" to "test-key"), authOf(azure))
        assertEquals(RequestStyle.ANTHROPIC, chosen.options.wire)
        assertEquals(mapOf("x-api-key" to "test-key"), authOf(chosen))
        // Plan 4B counts a deployment as Claude only when its name starts with "claude".
        val named = resolve(profile("microsoft-foundry", "microsoft-foundry-apikey", model = ProfileModel("claude-team"), base = "https://test.services.ai.azure.com"))
        assertEquals(RequestStyle.ANTHROPIC, named.options.wire)
        assertEquals(mapOf("x-api-key" to "test-key"), authOf(named))
    }

    @Test
    fun vertexUsesTheLocationAndTheKeyFilesProject() {
        vault.stored["test-credential"] = Credential.ServiceAccountKey("test@example.iam.gserviceaccount.com", "-----BEGIN PRIVATE KEY-----", null, "test-project")
        val claude = catalog.provider("google-vertex")!!.models.first { it.style == RequestStyle.ANTHROPIC }

        val target = resolve(profile("google-vertex", "vertex-service-account", AuthMethod.IMPORTED, ProfileModel(claude.id), region = "europe-west4"))

        assertEquals(RequestStyle.VERTEX, target.style)
        assertEquals(VertexUrls.base("europe-west4", "test-project"), target.baseUrl)
        assertEquals(RequestStyle.ANTHROPIC, target.options.wire)
        // A project ID that is not in Google's form never becomes part of the path.
        vault.stored["test-credential-2"] = Credential.ServiceAccountKey("test@example.iam.gserviceaccount.com", "-----BEGIN PRIVATE KEY-----", null, "Bad/Project")
        try {
            resolve(profile("google-vertex", "vertex-service-account", AuthMethod.IMPORTED, ProfileModel(claude.id), region = "europe-west4", credential = "test-credential-2"))
            fail("expected a ProviderException")
        } catch (e: ProviderException) {
            assertEquals(ProviderError.NotAllowed, e.error)
        }
    }

    @Test
    fun aLocalOllamaServerUsesItsOriginAndTheOwnersSwitches() {
        val target = resolve(
            profile(
                "ollama", "ollama-server", AuthMethod.SERVER_ADDRESS, ProfileModel("llama-test", images = true, tools = false),
                base = "http://192.168.1.20:11434/v1", server = LocalServerKind.OLLAMA,
            ),
        )

        assertEquals(RequestStyle.OLLAMA, target.style)
        assertEquals("http://192.168.1.20:11434/", target.baseUrl.toString())
        assertEquals(ToolMode.JSON, target.model.toolMode)
        assertTrue(InputKind.IMAGE in target.model.input)
    }

    @Test
    fun aLocalServersRequestsUseTheBaseThatItsCheckUses() {
        val lmStudio = resolve(
            profile("lmstudio", "lmstudio-server", AuthMethod.SERVER_ADDRESS, base = "http://192.168.1.20:1234", server = LocalServerKind.LM_STUDIO),
        )
        val ollama = resolve(
            profile("ollama", "ollama-server", AuthMethod.SERVER_ADDRESS, base = "http://192.168.1.20/ollama/v1", server = LocalServerKind.OLLAMA),
        )

        assertEquals(RequestStyle.CHAT_COMPLETIONS, lmStudio.style)
        assertEquals("http://192.168.1.20:1234/v1", lmStudio.baseUrl.toString())
        assertEquals(RequestStyle.OLLAMA, ollama.style)
        assertEquals("http://192.168.1.20/ollama", ollama.baseUrl.toString())
    }

    @Test
    fun aModelThatOnlyTheListKnowsGetsWhatTheListReportsAndJsonActionsAreTheOwnersChoice() {
        val listed = DiscoveredModel("listed-model", "Listed", setOf(InputKind.TEXT, InputKind.IMAGE), reasoning = true, reasoningEfforts = listOf("low", "high"), tools = true)
        val target = resolve(profile("groq", "groq-api-key", model = ProfileModel("listed-model", jsonActions = true)), listOf(listed))

        assertEquals(setOf(InputKind.TEXT, InputKind.IMAGE), target.model.input)
        assertEquals(listOf("low", "high"), target.model.compat.supportedReasoningEfforts)
        assertEquals(ToolMode.JSON, target.model.toolMode)
        assertEquals(ModelEntry("x", "x"), ModelEntries.entryFor(null, "x", emptyList()))
    }

    @Test
    fun aProviderWithItsOwnTargetsResolvesThemItself() {
        val own = ModelTarget("github-copilot", RequestStyle.RESPONSES, "https://api.individual.githubcopilot.com".toHttpUrl(), ModelEntry("m", "m"), auth = NoAuth)

        val target = resolve(profile("github-copilot", "github-copilot", AuthMethod.DEVICE_CODE), special = mapOf("github-copilot" to ProviderTargets { _, _, _ -> own }))

        assertEquals(own, target)
    }
}
