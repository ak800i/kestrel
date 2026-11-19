package dev.kestrel.providers.discovery

import dev.kestrel.providers.catalog.Catalog
import dev.kestrel.providers.catalog.CatalogLoader
import dev.kestrel.providers.catalog.InputKind
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.http.NoAuth
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.styles.AdapterHarness
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Runs against the repository's catalog, so that each provider's entry decides its list as it will in the app. The model
 * lists follow the providers' documented formats, because no request went to a provider: OpenRouter's "List all models",
 * Anthropic's "List Models" (`GET /v1/models`, paged by `after_id`), Google's `models.list` (paged by `pageToken`) and
 * Mantle's OpenAI-compatible `/v1/models`.
 */
class DiscoveriesTest {
    private val bench = AdapterHarness()
    private val catalog: Catalog = File(System.getProperty("kestrel.catalog") ?: error("The build passes -Dkestrel.catalog")).let {
        CatalogLoader.load(it.resolve("providers.json").readText(), it.resolve("overrides.json").readText())
    }

    @After
    fun close() = bench.close()

    private fun list(provider: String, base: String, region: String? = null): List<DiscoveredModel>? = runBlocking {
        Discoveries.forProvider(catalog.provider(provider)!!, base.toHttpUrl(), region, NoAuth, bench.http)?.list()
    }

    @Test
    fun aChatCompletionsProviderListsItsModelsWithWhatTheListReports() {
        bench.reply("chat-completions/models.json")

        val models = list("openrouter", "https://openrouter.ai/api/v1")!!

        assertEquals(
            listOf(
                DiscoveredModel("anthropic/claude-sonnet-5", "Anthropic: Claude Sonnet 5", setOf(InputKind.TEXT, InputKind.IMAGE), reasoning = true, contextWindow = 1000000, maxTokens = 128000, tools = true),
                DiscoveredModel("mistralai/mistral-small", "Mistral: Mistral Small", contextWindow = 32768, tools = false),
                DiscoveredModel("plain-model", "plain-model"),
            ),
            models,
        )
        assertEquals(listOf("https://openrouter.ai/api/v1/models".toHttpUrl()), bench.calls.requests)
    }

    @Test
    fun anthropicsListIsReadPageByPageWithItsVersionHeader() {
        bench.reply("anthropic/models-1.json")
        bench.reply("anthropic/models-2.json")

        val models = list("anthropic", "https://api.anthropic.com")!!

        assertEquals(listOf("claude-sonnet-5", "claude-haiku-4-5"), models.map { it.id })
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), models[0].reasoningEfforts)
        assertNull(models[1].reasoningEfforts)
        assertEquals(setOf(InputKind.TEXT, InputKind.IMAGE), models[0].input)
        assertEquals(
            listOf("https://api.anthropic.com/v1/models?limit=1000", "https://api.anthropic.com/v1/models?limit=1000&after_id=claude-sonnet-5").map { it.toHttpUrl() },
            bench.calls.requests,
        )
        assertEquals("2023-06-01", bench.take().headers["anthropic-version"])
    }

    @Test
    fun geminiListsOnlyTheModelsThatGenerateContent() {
        bench.reply("gemini/models.json")

        val models = list("google", "https://generativelanguage.googleapis.com/v1beta")!!

        assertEquals(listOf(DiscoveredModel("gemini-3.8-flash", "Gemini 3.8 Flash", reasoning = true, contextWindow = 1048576, maxTokens = 65536)), models)
        assertEquals(listOf("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000".toHttpUrl()), bench.calls.requests)
    }

    @Test
    fun mantleListsAtItsRegionsV1ModelsAndItsClaudeRowsStayInTheCatalog() {
        bench.reply("bedrock/mantle-models.json")

        val models = list("amazon-bedrock-mantle", "https://bedrock-mantle.eu-west-1.api.aws/anthropic", region = "eu-west-1")!!

        assertEquals(listOf("openai.gpt-oss-120b", "qwen.qwen3-235b-a22b"), models.map { it.id })
        assertEquals(listOf("https://bedrock-mantle.eu-west-1.api.aws/v1/models".toHttpUrl()), bench.calls.requests)
        val claude = catalog.provider("amazon-bedrock-mantle")!!.models.filter { it.style == RequestStyle.ANTHROPIC }.map { it.id }
        assertEquals(
            listOf("anthropic.claude-mythos-5", "anthropic.claude-mythos-preview", "anthropic.claude-opus-4-7", "anthropic.claude-opus-5", "anthropic.claude-sonnet-5"),
            claude,
        )
        assertTrue(claude.none { id -> models.any { it.id == id } })
    }

    @Test
    fun providersWithoutAListGetNone() {
        assertNull(Discoveries.forProvider(catalog.provider("synthetic")!!, "https://api.synthetic.new/anthropic".toHttpUrl(), null, NoAuth, bench.http))
        assertNull(Discoveries.forProvider(catalog.provider("google-vertex")!!, "https://aiplatform.googleapis.com".toHttpUrl(), "global", NoAuth, bench.http))
        assertNull(Discoveries.forProvider(catalog.provider("microsoft-foundry")!!, "https://kestrel.openai.azure.com".toHttpUrl(), null, NoAuth, bench.http))
        assertNull(Discoveries.forProvider(catalog.provider("amazon-bedrock")!!, "https://bedrock-runtime.us-east-1.amazonaws.com".toHttpUrl(), null, NoAuth, bench.http))
        assertNull(Discoveries.forProvider(catalog.provider("amazon-bedrock-mantle")!!, "https://bedrock-mantle.eu-west-1.api.aws/v1".toHttpUrl(), null, NoAuth, bench.http))
        assertEquals(0, bench.server.requestCount)
    }

    private fun errorOf(provider: String, base: String): ProviderError =
        try {
            list(provider, base)
            throw AssertionError("expected a ProviderException")
        } catch (e: ProviderException) {
            e.error
        }

    @Test
    fun anthropicsPagesStopWhenALastIdRepeatsAndAfterFiftyPages() {
        repeat(2) { bench.status(200, """{"data":[],"has_more":true,"last_id":"again"}""") }
        bench.status(200, """{"data":[],"has_more":false}""")

        list("anthropic", "https://api.anthropic.com")

        assertEquals(2, bench.server.requestCount)
        AdapterHarness().use { other ->
            repeat(60) { other.status(200, """{"data":[],"has_more":true,"last_id":"page-$it"}""") }
            other.status(200, """{"data":[],"has_more":false}""")
            runBlocking { Discoveries.forProvider(catalog.provider("anthropic")!!, "https://api.anthropic.com".toHttpUrl(), null, NoAuth, other.http)!!.list() }
            assertEquals(50, other.server.requestCount)
        }
    }

    @Test
    fun geminisSecondPageIsReadAndItsPagesStopWhenATokenRepeats() {
        bench.status(200, """{"models":[{"name":"models/gemini-3.8-flash","supportedGenerationMethods":["generateContent"]}],"nextPageToken":"page-2"}""")
        bench.status(200, """{"models":[{"name":"models/gemini-4-argon","supportedGenerationMethods":["generateContent"]}],"nextPageToken":"page-2"}""")
        bench.status(200, """{"models":[]}""")

        val models = list("google", "https://generativelanguage.googleapis.com/v1beta")!!

        assertEquals(listOf("gemini-3.8-flash", "gemini-4-argon"), models.map { it.id })
        assertEquals(
            listOf(
                "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000",
                "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000&pageToken=page-2",
            ).map { it.toHttpUrl() },
            bench.calls.requests,
        )
    }

    @Test
    fun aGeminiKeyThatGoogleRefusesIsTheCredentialsRejection() {
        bench.status(
            400,
            """{"error":{"code":400,"message":"API key not valid.","status":"INVALID_ARGUMENT",""" +
                """"details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"API_KEY_INVALID"}]}}""",
        )

        assertEquals(ProviderError.Unauthorized, errorOf("google", "https://generativelanguage.googleapis.com/v1beta"))
    }

    @Test
    fun aListThatIsNotJsonIsMalformed() {
        bench.status(200, "<html>Not a list</html>")

        assertEquals(ProviderError.Malformed, errorOf("openrouter", "https://openrouter.ai/api/v1"))
    }

    @Test
    fun aResponsesProviderListsOnlyItsChatModelsAndANumberAboveIntMaxIsUnknown() {
        bench.status(
            200,
            """{"data":[{"id":"gpt-5.5","context_window":3000000000},{"id":"text-embedding-3-small"},{"id":"whisper-1"},""" +
                """{"id":"tts-1-hd"},{"id":"gpt-4o-mini-tts"},{"id":"dall-e-3"},{"id":"gpt-image-1"},{"id":"omni-moderation-latest"},""" +
                """{"id":"gpt-realtime"},{"id":"gpt-4o-transcribe"}]}""",
        )

        val models = list("openai", "https://api.openai.com/v1")!!

        assertEquals(listOf(DiscoveredModel("gpt-5.5", "gpt-5.5")), models)
        assertEquals(listOf("https://api.openai.com/v1/models".toHttpUrl()), bench.calls.requests)
    }
}
