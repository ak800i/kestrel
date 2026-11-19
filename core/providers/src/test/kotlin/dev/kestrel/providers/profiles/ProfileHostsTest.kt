package dev.kestrel.providers.profiles

import dev.kestrel.providers.catalog.AuthMethod
import dev.kestrel.providers.catalog.Catalog
import dev.kestrel.providers.catalog.CatalogLoader
import dev.kestrel.providers.catalog.LocalServerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** Runs against the repository's catalog, so that it also checks the base URLs and hosts that overrides.json gives. */
class ProfileHostsTest {
    private val catalog: Catalog = File(System.getProperty("kestrel.catalog") ?: error("The build passes -Dkestrel.catalog")).let {
        CatalogLoader.load(it.resolve("providers.json").readText(), it.resolve("overrides.json").readText())
    }

    private fun profile(provider: String, choice: String, method: AuthMethod, baseUrl: String? = null, region: String? = null, discovered: List<String> = emptyList()) =
        Profile(
            id = "test-1",
            name = "Test",
            providerId = provider,
            choiceId = choice,
            method = method,
            credentialId = "test-credential",
            baseUrl = baseUrl,
            region = region,
            lightweight = ProfileModel("a"),
            versatile = ProfileModel("b"),
            discoveredHosts = discovered,
        )

    @Test
    fun anXaiSignInAllowsTheProxyAndNotTheApiWhileAKeyAllowsTheApi() {
        val signIn = profile("xai", "xai-oauth", AuthMethod.DEVICE_CODE, discovered = listOf("auth.x.ai"))
        val key = profile("xai", "xai-api-key", AuthMethod.API_KEY)

        assertEquals("https://cli-chat-proxy.grok.com/v1", ProfileHosts.baseUrl(signIn, catalog).toString())
        assertEquals(setOf("auth.x.ai", "cli-chat-proxy.grok.com"), ProfileHosts.hosts(signIn, catalog))
        assertEquals(setOf("api.x.ai"), ProfileHosts.hosts(key, catalog))
    }

    @Test
    fun aChatGptPlanProfileAllowsTheApiAndTheSignInHost() {
        val plan = profile("openai", "chatgpt-plan", AuthMethod.BROWSER)

        assertEquals("https://api.openai.com/v1", ProfileHosts.baseUrl(plan, catalog).toString())
        assertEquals(setOf("api.openai.com", "auth.openai.com"), ProfileHosts.hosts(plan, catalog))
    }

    @Test
    fun regionsFillTheBedrockMantleAndVertexHosts() {
        val bedrock = profile("amazon-bedrock", "bedrock-iam-keys", AuthMethod.IMPORTED, region = "eu-west-1")
        val mantle = profile("amazon-bedrock-mantle", "mantle-api-key", AuthMethod.API_KEY, region = "us-east-1")
        val vertexGlobal = profile("google-vertex", "vertex-service-account", AuthMethod.IMPORTED, region = "global")
        val vertexLocation = vertexGlobal.copy(region = "europe-west4")

        assertEquals("https://bedrock-runtime.eu-west-1.amazonaws.com/", ProfileHosts.baseUrl(bedrock, catalog).toString())
        assertEquals(setOf("bedrock-runtime.eu-west-1.amazonaws.com", "bedrock.eu-west-1.amazonaws.com"), ProfileHosts.hosts(bedrock, catalog))
        assertEquals(setOf("bedrock-mantle.us-east-1.api.aws"), ProfileHosts.hosts(mantle, catalog))
        assertEquals(setOf("aiplatform.googleapis.com", "oauth2.googleapis.com"), ProfileHosts.hosts(vertexGlobal, catalog))
        assertEquals(setOf("europe-west4-aiplatform.googleapis.com", "oauth2.googleapis.com"), ProfileHosts.hosts(vertexLocation, catalog))
    }

    @Test
    fun aMissingOrUnsafeRegionGivesNoBaseUrlAndNoTemplateHost() {
        val noRegion = profile("amazon-bedrock", "bedrock-api-key", AuthMethod.API_KEY)
        val unsafe = noRegion.copy(region = "evil.example.com/x")

        assertNull(ProfileHosts.baseUrl(noRegion, catalog))
        assertEquals(emptySet<String>(), ProfileHosts.hosts(noRegion, catalog))
        assertNull(ProfileHosts.baseUrl(unsafe, catalog))
        assertEquals(emptySet<String>(), ProfileHosts.hosts(unsafe, catalog))
    }

    @Test
    fun theProfilesOwnBaseUrlWinsAndItsDiscoveredHostsCount() {
        val minimax = profile(
            "minimax-portal", "minimax-global-oauth", AuthMethod.DEVICE_CODE,
            baseUrl = "https://api-eu.minimax.io/anthropic", discovered = listOf("account.minimax.io"),
        )

        assertEquals(setOf("account.minimax.io", "api-eu.minimax.io"), ProfileHosts.hosts(minimax, catalog))
    }

    @Test
    fun localServersGoToTheLocalListAndPublicAddressesToTheHosts() {
        val lan = profile("ollama", "ollama-server", AuthMethod.SERVER_ADDRESS, baseUrl = "http://192.168.1.20:11434").copy(localServer = LocalServerKind.OLLAMA)
        val phone = lan.copy(baseUrl = "http://127.0.0.1:11434")
        val proxy = profile("litellm", "litellm-server", AuthMethod.SERVER_ADDRESS, baseUrl = "https://llm.example.com/v1")

        assertEquals(emptySet<String>(), ProfileHosts.hosts(lan, catalog))
        assertEquals(setOf("192.168.1.20"), ProfileHosts.localServers(lan))
        assertEquals(setOf("127.0.0.1"), ProfileHosts.localServers(phone))
        assertEquals(setOf("llm.example.com"), ProfileHosts.hosts(proxy, catalog))
        assertEquals(emptySet<String>(), ProfileHosts.localServers(proxy))
    }

    @Test
    fun copilotAllowsItsDefaultApiAndTheHostsThatItsSignInFound() {
        val copilot = profile("github-copilot", "github-copilot", AuthMethod.DEVICE_CODE, discovered = listOf("api.github.com", "api.business.githubcopilot.com"))

        assertEquals(
            setOf("api.business.githubcopilot.com", "api.github.com", "api.individual.githubcopilot.com"),
            ProfileHosts.hosts(copilot, catalog),
        )
    }
}
