package dev.kestrel.providers.discovery

import dev.kestrel.providers.catalog.InputKind
import dev.kestrel.providers.catalog.LocalServerKind
import dev.kestrel.providers.http.NoAuth
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.styles.AdapterHarness
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class LocalServerDiscoveryTest {
    private val bench = AdapterHarness()

    @After
    fun close() = bench.close()

    private fun list(kind: LocalServerKind, address: String) =
        runBlocking { Discoveries.forLocalServer(kind, address.toHttpUrl(), NoAuth, bench.http).list() }

    @Test
    fun ollamaListsItsTagsAndAsksEachModelForItsCapabilitiesAtTheOrigin() {
        bench.reply("local-servers/ollama-tags.json")
        bench.reply("local-servers/ollama-show-qwen.json")
        bench.reply("local-servers/ollama-show-llama.json")
        bench.reply("local-servers/ollama-show-embed.json")
        bench.status(500, """{"error":"model runner has unexpectedly stopped"}""")

        val models = list(LocalServerKind.OLLAMA, "http://192.168.1.20:11434/v1")

        // The embedding model is left out, and a model whose /api/show fails keeps unknown capabilities.
        assertEquals(
            listOf(
                DiscoveredModel("qwen3.6:27b", "qwen3.6:27b", setOf(InputKind.TEXT, InputKind.IMAGE), reasoning = true, contextWindow = 262144, tools = true),
                DiscoveredModel("llama3.2:3b", "llama3.2:3b", setOf(InputKind.TEXT), contextWindow = 131072, tools = false),
                DiscoveredModel("mistral:7b", "mistral:7b", setOf(InputKind.TEXT)),
            ),
            models,
        )
        assertEquals(
            listOf("http://192.168.1.20:11434/api/tags".toHttpUrl()) + List(4) { "http://192.168.1.20:11434/api/show".toHttpUrl() },
            bench.calls.requests,
        )
        bench.take()
        assertEquals("""{"model":"qwen3.6:27b"}""", bench.take().body!!.utf8())
    }

    @Test
    fun lmStudioListsOnlyItsLanguageModelsWithTheirVisionAndToolFlags() {
        bench.reply("local-servers/lmstudio-models.json")

        val models = list(LocalServerKind.LM_STUDIO, "http://127.0.0.1:1234/v1")

        assertEquals(
            listOf(
                DiscoveredModel("qwen/qwen3.6-27b", "Qwen3.6 27B", setOf(InputKind.TEXT, InputKind.IMAGE), reasoning = true, contextWindow = 262144, tools = true),
                DiscoveredModel("google/gemma-4-e4b", "Gemma 4 E4B", setOf(InputKind.TEXT), contextWindow = 131072, tools = false),
            ),
            models,
        )
        assertEquals(listOf("http://127.0.0.1:1234/api/v1/models".toHttpUrl()), bench.calls.requests)
    }

    @Test
    fun llamaCppReadsItsModelAndItsProps() {
        bench.reply("local-servers/llamacpp-models.json")
        bench.reply("local-servers/llamacpp-props.json")

        val models = list(LocalServerKind.LLAMA_CPP, "http://10.0.0.5:8080/v1")

        assertEquals(listOf(DiscoveredModel("gemma-4-31b-q4", "gemma-4-31b-q4", setOf(InputKind.TEXT, InputKind.IMAGE), contextWindow = 32768, tools = true)), models)
        assertEquals(listOf("http://10.0.0.5:8080/v1/models", "http://10.0.0.5:8080/props").map { it.toHttpUrl() }, bench.calls.requests)
    }

    @Test
    fun theOtherServersListTheirModelsAndReportNoCapabilities() {
        bench.reply("local-servers/vllm-models.json")

        val models = list(LocalServerKind.VLLM, "http://127.0.0.1:8000/v1")

        assertEquals(listOf(DiscoveredModel("Qwen/Qwen3.6-27B", "Qwen/Qwen3.6-27B", contextWindow = 131072)), models)
        assertEquals(listOf("http://127.0.0.1:8000/v1/models".toHttpUrl()), bench.calls.requests)
    }

    @Test
    fun aServerThatAnswersWithoutJsonIsMalformed() {
        bench.status(200, "<html>Not an API</html>")

        try {
            list(LocalServerKind.SGLANG, "http://127.0.0.1:30000/v1")
            fail("expected a ProviderException")
        } catch (e: ProviderException) {
            assertEquals(ProviderError.Malformed, e.error)
        }
    }
}
