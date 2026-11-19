package dev.kestrel.providers.styles

import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.chat.StopReason
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.http.RequestAuth
import dev.kestrel.providers.styles.AdapterHarness.Companion.json
import dev.kestrel.providers.styles.AdapterHarness.Companion.obj
import dev.kestrel.providers.styles.AdapterHarness.Companion.request
import dev.kestrel.providers.styles.AdapterHarness.Companion.user
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VertexAdapterTest {
    private val bench = AdapterHarness()
    private val logPaths = mutableListOf<String?>()
    private val adapter = VertexAdapter(ProviderHttp(bench.calls, decorator = { _, meta -> logPaths += meta.logPath }))
    private val token = object : RequestAuth {
        override suspend fun headers(request: HttpRequest) = mapOf("Authorization" to "Bearer ya29.test")
    }
    private val base = VertexUrls.base("europe-west4", "kestrel-test-project")

    @After
    fun close() = bench.close()

    private fun vertex(model: ModelEntry) = AdapterHarness.target("google-vertex", RequestStyle.VERTEX, base.toString(), model, token)

    @Test
    fun aGeminiModelStreamsThroughTheGeminiAdapterAndTheLogHidesTheProject() = runBlocking {
        bench.reply("vertex/gemini-text.sse")

        val response = adapter.complete(request(user("Hi")), vertex(ModelEntry("gemini-3.8-flash", "Gemini 3.8 Flash", style = RequestStyle.GEMINI)))

        assertEquals(listOf(AssistantPart.Text("Hello from Gemini on Vertex.")), response.message.parts)
        assertEquals(
            "https://europe-west4-aiplatform.googleapis.com/v1/projects/kestrel-test-project/locations/europe-west4/publishers/google/models/gemini-3.8-flash:streamGenerateContent?alt=sse".toHttpUrl(),
            bench.calls.requests.single(),
        )
        assertEquals(listOf<String?>("/v1/projects/{project}/locations/europe-west4/publishers/google/models/gemini-3.8-flash:streamGenerateContent"), logPaths)
        val recorded = bench.take()
        assertEquals("Bearer ya29.test", recorded.headers["Authorization"])
        assertNull(recorded.headers["x-goog-api-key"])
    }

    @Test
    fun aClaudeModelUsesRawPredictWithTheVertexVersion() = runBlocking {
        bench.reply("vertex/claude-message.json")

        val response = adapter.complete(request(user("Open the camera.")), vertex(ModelEntry("claude-sonnet-4-6", "Claude Sonnet 4.6", style = RequestStyle.ANTHROPIC)))

        assertEquals(listOf(AssistantPart.ToolCall("toolu_v", "open_app", obj("""{"name":"Camera"}"""))), response.message.parts)
        assertEquals(StopReason.TOOL_CALL, response.stop)
        assertEquals(
            "https://europe-west4-aiplatform.googleapis.com/v1/projects/kestrel-test-project/locations/europe-west4/publishers/anthropic/models/claude-sonnet-4-6:rawPredict".toHttpUrl(),
            bench.calls.requests.single(),
        )
        assertEquals(listOf<String?>("/v1/projects/{project}/locations/europe-west4/publishers/anthropic/models/claude-sonnet-4-6:rawPredict"), logPaths)
        val body = bench.body(bench.take())
        assertEquals(json(""""vertex-2023-10-16""""), body["anthropic_version"])
        assertEquals(json("false"), body["stream"])
    }

    @Test
    fun aModelWithoutAStyleIsClaudeByItsIdAndGeminiOtherwise() = runBlocking {
        bench.reply("vertex/claude-message.json")
        bench.reply("vertex/gemini-text.sse")

        adapter.complete(request(user("Hi")), vertex(ModelEntry("claude-opus-5", "Claude Opus 5")))
        adapter.complete(request(user("Hi"), ChatMessage.Assistant(listOf(AssistantPart.Text("Hello.")))), vertex(ModelEntry("gemini-4-argon", "Gemini 4 Argon")))

        assertTrue(bench.calls.requests[0].encodedPath.endsWith("/publishers/anthropic/models/claude-opus-5:rawPredict"))
        assertTrue(bench.calls.requests[1].encodedPath.endsWith("/publishers/google/models/gemini-4-argon:streamGenerateContent"))
    }

}
