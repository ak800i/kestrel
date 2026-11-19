package dev.kestrel.providers.styles

import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ImageType
import dev.kestrel.providers.chat.InvalidKind
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.chat.ReasoningLevel
import dev.kestrel.providers.chat.ReasoningState
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.chat.StopReason
import dev.kestrel.providers.chat.StyleOptions
import dev.kestrel.providers.chat.Usage
import dev.kestrel.providers.http.ApiKeyAuth
import dev.kestrel.providers.http.KeyHeader
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.styles.AdapterHarness.Companion.GET_NUMBER
import dev.kestrel.providers.styles.AdapterHarness.Companion.OPEN_APP
import dev.kestrel.providers.styles.AdapterHarness.Companion.SCREENSHOT
import dev.kestrel.providers.styles.AdapterHarness.Companion.errorOf
import dev.kestrel.providers.styles.AdapterHarness.Companion.json
import dev.kestrel.providers.styles.AdapterHarness.Companion.obj
import dev.kestrel.providers.styles.AdapterHarness.Companion.request
import dev.kestrel.providers.styles.AdapterHarness.Companion.target
import dev.kestrel.providers.styles.AdapterHarness.Companion.user
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiAdapterTest {
    private val bench = AdapterHarness()
    private val adapter = GeminiAdapter(bench.http)

    private val flash = ModelEntry("gemini-3.8-flash", "Gemini 3.8 Flash", reasoning = true, thinkingLevelMap = mapOf("minimal" to null))
    private val google = target("google", RequestStyle.GEMINI, "https://generativelanguage.googleapis.com/v1beta", flash, ApiKeyAuth("test-key", KeyHeader.X_GOOG_API_KEY))

    @After
    fun close() = bench.close()

    @Test
    fun aTextAnswerIsGatheredWithItsUsageAndAToolWithoutPropertiesHasNoParameters() = runBlocking {
        bench.reply("gemini/text.sse")

        val response = adapter.complete(request(user("Hi"), tools = listOf(OPEN_APP, GET_NUMBER)) { copy(reasoning = ReasoningLevel.LOW) }, google)

        assertEquals(listOf(AssistantPart.Text("Hello, owner.")), response.message.parts)
        assertEquals(StopReason.END, response.stop)
        assertEquals(Usage(input = 410, output = 50, cacheRead = 600, reasoning = 20), response.usage)
        assertEquals(
            listOf("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:streamGenerateContent?alt=sse".toHttpUrl()),
            bench.calls.requests,
        )
        val recorded = bench.take()
        assertEquals("test-key", recorded.headers["x-goog-api-key"])
        bench.assertBody("gemini/text.request.json", recorded)
    }

    @Test
    fun theModelsPartsGoBackWithTheirSignatureAndARebuiltMessageGetsThePlaceholder() = runBlocking {
        bench.reply("gemini/tool-call.sse")
        bench.reply("gemini/text.sse")
        bench.reply("gemini/tool-call.sse")
        bench.reply("gemini/text.sse")
        bench.reply("gemini/unknown-call.sse")
        bench.reply("gemini/text.sse")

        val first = adapter.complete(request(user("Open the camera.")), google)

        val parts = json("""[{"functionCall":{"name":"open_app","args":{"name":"Camera"}},"thoughtSignature":"c2lnLW9uZQ=="}]""")
        assertEquals(
            listOf(
                AssistantPart.Reasoning(ReasoningState(RequestStyle.GEMINI, "google", "gemini-3.8-flash", obj("""{"parts":$parts}"""))),
                AssistantPart.ToolCall("gemini-call-0", "open_app", obj("""{"name":"Camera"}""")),
            ),
            first.message.parts,
        )
        assertEquals(StopReason.TOOL_CALL, first.stop)
        val next = request(user("Open the camera."), first.message, ChatMessage.ToolResult("gemini-call-0", "open_app", "Opened."))
        adapter.complete(next, google)
        // The agent's form: after the results comes the next screen, also as a user message.
        val argon = google.copy(model = ModelEntry("gemini-4-argon", "Gemini 4 Argon", reasoning = true), sentModelId = "gemini-4-argon")
        val withScreen = request(user("Open the camera."), first.message, ChatMessage.ToolResult("gemini-call-0", "open_app", "Opened."), user("The screen now."))
        val second = adapter.complete(withScreen, argon)
        val pro25 = google.copy(model = ModelEntry("gemini-2.5-pro", "Gemini 2.5 Pro", reasoning = true), sentModelId = "gemini-2.5-pro")
        adapter.complete(next, pro25)
        // A reply that holds a call that Kestrel leaves out is rebuilt, so that every call has its response.
        val partial = adapter.complete(request(user("Open the camera.")), google)
        adapter.complete(request(user("Open the camera."), partial.message, ChatMessage.ToolResult(partial.message.toolCalls.single().id, "open_app", "Opened.")), google)

        bench.take()
        val same = bench.body(bench.take())["contents"]!!.jsonArray
        assertEquals(json("""{"role":"model","parts":$parts}"""), same[1])
        assertEquals(json("""{"role":"user","parts":[{"functionResponse":{"name":"open_app","response":{"output":"Opened."}}}]}"""), same[2])
        val placeholder = json("""{"role":"model","parts":[{"functionCall":{"name":"open_app","args":{"name":"Camera"}},"thoughtSignature":"skip_thought_signature_validator"}]}""")
        assertEquals(placeholder, bench.body(bench.take())["contents"]!!.jsonArray[1])
        // A made-up ID is numbered after the highest one in the history.
        assertEquals("gemini-call-1", second.message.toolCalls.single().id)
        assertEquals(json("""{"role":"model","parts":[{"functionCall":{"name":"open_app","args":{"name":"Camera"}}}]}"""), bench.body(bench.take())["contents"]!!.jsonArray[1])
        bench.take()
        assertEquals(InvalidKind.UNKNOWN_TOOL, partial.invalidToolCall?.kind)
        assertEquals(placeholder, bench.body(bench.take())["contents"]!!.jsonArray[1])
    }

    @Test
    fun allResultsOfAStepGoTogetherWithTheNextScreen() = runBlocking {
        bench.reply("gemini/two-calls.sse")
        bench.reply("gemini/text.sse")

        val first = adapter.complete(request(user("Open the camera and the clock.")), google)

        assertEquals(listOf("gemini-call-0", "gemini-call-1"), first.message.toolCalls.map { it.id })
        val next = request(
            user("Open the camera and the clock."),
            first.message,
            ChatMessage.ToolResult("gemini-call-0", "open_app", "Opened."),
            ChatMessage.ToolResult("gemini-call-1", "open_app", "Not run: Kestrel runs one action per step", failed = true),
            ChatMessage.User(listOf(Part.Text("The screen now."), Part.Text(" "), Part.Image(ImageType.PNG, SCREENSHOT))),
        ) { copy(system = "\n") }
        adapter.complete(next, google)

        bench.take()
        val body = bench.body(bench.take())
        // Gemini refuses a part without data, so the blank system prompt and the blank text are left out.
        assertNull(body["systemInstruction"])
        val contents = body["contents"]!!.jsonArray
        assertEquals(3, contents.size)
        assertEquals(
            json(
                """[{"functionResponse":{"name":"open_app","response":{"output":"Opened."}}},
                {"functionResponse":{"name":"open_app","response":{"error":"Not run: Kestrel runs one action per step"}}},
                {"text":"The screen now."},{"inlineData":{"mimeType":"image/png","data":"iVBORw0KGgo="}}]""",
            ),
            contents[2].jsonObject["parts"],
        )
    }

    @Test
    fun jsonActionsAskForJsonWithoutASchemaAndTheHistoryIsText() = runBlocking {
        bench.reply("gemini/no-usage.sse")
        val call = AssistantPart.ToolCall("gemini-call-0", "open_app", obj("""{"name":"Camera"}"""))
        val history = request(user("Open the camera."), ChatMessage.Assistant(listOf(call)), ChatMessage.ToolResult("gemini-call-0", "open_app", "Opened."), tools = emptyList()) {
            copy(jsonSchema = obj("""{"type":"object"}"""))
        }

        val response = adapter.complete(history, google)

        assertTrue(response.usage.estimated)
        val body = bench.body(bench.take())
        assertNull(body["tools"])
        assertEquals(json(""""application/json""""), body["generationConfig"]!!.jsonObject["responseMimeType"])
        assertEquals(
            json("""[{"role":"user","parts":[{"text":"Open the camera."}]},{"role":"model","parts":[{"text":"[tool call id=gemini-call-0 name=open_app] {\"name\":\"Camera\"}"}]},{"role":"user","parts":[{"text":"[tool result id=gemini-call-0 name=open_app] Opened."}]}]"""),
            body["contents"],
        )
    }

    @Test
    fun theStatusErrorsAndTheStreamsErrorAreMapped() {
        bench.status(401)
        bench.status(401)
        bench.status(429, """{"error":{"code":429,"message":"Quota.","status":"RESOURCE_EXHAUSTED"}}""", "Retry-After" to "7")
        bench.status(500)
        bench.reply("gemini/stream-error.sse")
        val auth = AdapterHarness.RefreshingAuth()

        assertEquals(ProviderError.Unauthorized, errorOf(adapter, request(user("Hi")), google.copy(auth = auth)))
        assertEquals(1, auth.refreshes)
        assertEquals(ProviderError.RateLimited(7_000, "RESOURCE_EXHAUSTED"), errorOf(adapter, request(user("Hi")), google))
        assertEquals(ProviderError.ServerError(500), errorOf(adapter, request(user("Hi")), google))
        assertEquals(ProviderError.RateLimited(null, "RESOURCE_EXHAUSTED"), errorOf(adapter, request(user("Hi")), google))
    }

    @Test
    fun aMalformedChunkAndAStreamWithoutAFinishReasonAreMalformedAndGeminisOwnStopsAreMapped() = runBlocking {
        bench.reply("gemini/malformed.sse")
        bench.reply("gemini/ends-early.sse")
        bench.reply("gemini/malformed-call.sse")
        bench.reply("gemini/blocked.sse")

        assertEquals(ProviderError.Malformed, errorOf(adapter, request(user("Hi")), google))
        assertEquals(ProviderError.Malformed, errorOf(adapter, request(user("Hi")), google))
        assertEquals(InvalidKind.BAD_JSON, adapter.complete(request(user("Open the camera.")), google).invalidToolCall?.kind)
        assertEquals(StopReason.FILTERED, adapter.complete(request(user("Hi")), google).stop)
    }

    @Test
    fun theThinkingSettingsFollowOpenClawsRulesPerGeneration() {
        assertEquals(obj("""{"thinkingBudget":32768}"""), GeminiAdapter.thinking("high", "gemini-2.5-pro"))
        assertEquals(obj("""{"thinkingBudget":512}"""), GeminiAdapter.thinking("minimal", "gemini-2.5-flash-lite"))
        assertNull(GeminiAdapter.thinking("high", "gemini-2.0-flash"))
        assertEquals(obj("""{"thinkingLevel":"LOW"}"""), GeminiAdapter.thinking("minimal", "gemini-3.1-pro-preview"))
        assertEquals(obj("""{"thinkingLevel":"HIGH"}"""), GeminiAdapter.thinking("medium", "gemini-3.1-pro-preview"))
        assertEquals(obj("""{"thinkingLevel":"MINIMAL"}"""), GeminiAdapter.thinking("minimal", "gemini-3.6-flash"))
        assertEquals(obj("""{"thinkingLevel":"LOW"}"""), GeminiAdapter.thinking("minimal", "gemini-3.7-flash"))
        assertEquals(obj("""{"thinkingLevel":"LOW"}"""), GeminiAdapter.thinking("minimal", "gemini-4-argon"))
        assertEquals(obj("""{"thinkingLevel":"HIGH"}"""), GeminiAdapter.thinking("max", "gemini-3.8-flash"))
    }

    @Test
    fun aStreamThatGathersMoreThan8MiBIsMalformed() {
        val piece = "a".repeat(512 * 1024)
        val chunk = """data: {"candidates":[{"content":{"parts":[{"text":"$piece"}],"role":"model"},"index":0}]}""" + "\n\n"
        val last = """data: {"candidates":[{"content":{"parts":[{"text":"."}],"role":"model"},"index":0,"finishReason":"STOP"}]}""" + "\n\n"
        bench.status(200, chunk.repeat(17) + last, "Content-Type" to "text/event-stream")

        assertEquals(ProviderError.Malformed, errorOf(adapter, request(user("Hi")), google))
    }

    @Test
    fun thoughtSignaturesCountTowardsTheGatherLimit() {
        val signature = "a".repeat(512 * 1024)
        val chunk = """data: {"candidates":[{"content":{"parts":[{"text":"","thoughtSignature":"$signature"}],"role":"model"},"index":0}]}""" + "\n\n"
        val last = """data: {"candidates":[{"content":{"parts":[{"text":"."}],"role":"model"},"index":0,"finishReason":"STOP"}]}""" + "\n\n"
        bench.status(200, chunk.repeat(17) + last, "Content-Type" to "text/event-stream")

        assertEquals(ProviderError.Malformed, errorOf(adapter, request(user("Hi")), google))
    }

    @Test
    fun withoutStreamingTheReplyIsOneBodyFromGenerateContent() = runBlocking {
        bench.status(
            200,
            """{"candidates":[{"content":{"parts":[{"text":"Hello, owner."}],"role":"model"},"finishReason":"STOP","index":0}],""" +
                """"usageMetadata":{"promptTokenCount":40,"candidatesTokenCount":5},"modelVersion":"gemini-3.8-flash"}""",
            "Content-Type" to "application/json",
        )

        val response = adapter.complete(request(user("Hi")), google.copy(options = StyleOptions(stream = false)))

        assertEquals(listOf(AssistantPart.Text("Hello, owner.")), response.message.parts)
        assertEquals(StopReason.END, response.stop)
        assertEquals(Usage(input = 40, output = 5), response.usage)
        assertEquals(listOf("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent".toHttpUrl()), bench.calls.requests)
        assertEquals("application/json", bench.take().headers["accept"])
    }
}
