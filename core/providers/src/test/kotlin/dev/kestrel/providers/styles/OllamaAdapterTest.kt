package dev.kestrel.providers.styles

import dev.kestrel.providers.catalog.InputKind
import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ImageType
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.chat.ReasoningLevel
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.chat.StopReason
import dev.kestrel.providers.chat.ToolChoice
import dev.kestrel.providers.chat.Usage
import dev.kestrel.providers.http.ProviderError
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

class OllamaAdapterTest {
    private val bench = AdapterHarness()
    private val adapter = OllamaAdapter(bench.http)

    private val ollama = target(
        "ollama", RequestStyle.OLLAMA, "http://127.0.0.1:11434/v1",
        ModelEntry("qwen3.6", "Qwen3.6", input = setOf(InputKind.TEXT, InputKind.IMAGE), reasoning = true, contextWindow = 262_144),
    )

    @After
    fun close() = bench.close()

    @Test
    fun aTextAnswerWithAnImageGoesToTheServersOriginWithoutStreaming() = runBlocking {
        // The request asks for a context of at most 32,768 tokens and, on a local server, for no truncation.
        bench.reply("ollama/text.json")
        val screen = ChatMessage.User(listOf(Part.Text("What is on the screen?"), Part.Image(ImageType.PNG, SCREENSHOT)))

        val response = adapter.complete(request(screen) { copy(reasoning = ReasoningLevel.LOW) }, ollama)

        assertEquals(listOf(AssistantPart.Text("Hello, owner.")), response.message.parts)
        assertEquals(StopReason.END, response.stop)
        assertEquals(Usage(input = 20, output = 8, cacheRead = 100), response.usage)
        assertEquals(listOf("http://127.0.0.1:11434/api/chat".toHttpUrl()), bench.calls.requests)
        val recorded = bench.take()
        assertEquals("application/json", recorded.headers["accept"])
        bench.assertBody("ollama/text.request.json", recorded)
    }

    @Test
    fun aToolCallAndItsResultGoBackWithTheToolsName() = runBlocking {
        bench.reply("ollama/tool-call.json")
        bench.reply("ollama/text.json")

        val first = adapter.complete(request(user("Open the camera.")), ollama)

        assertEquals(listOf(AssistantPart.ToolCall("ollama-call-0", "open_app", obj("""{"name":"Camera"}"""))), first.message.parts)
        assertEquals(StopReason.TOOL_CALL, first.stop)
        adapter.complete(request(user("Open the camera."), first.message, ChatMessage.ToolResult("ollama-call-0", "open_app", "Opened.")), ollama)

        bench.take()
        val messages = bench.body(bench.take())["messages"]!!.jsonArray
        assertEquals(json("""{"role":"assistant","content":"","tool_calls":[{"function":{"name":"open_app","arguments":{"name":"Camera"}}}]}"""), messages[2])
        assertEquals(json("""{"role":"tool","tool_name":"open_app","content":"Opened."}"""), messages[3])
    }

    @Test
    fun aReplyWithTwoCallsKeepsBothAndTheNextRequestAnswersBoth() = runBlocking {
        bench.reply("ollama/two-calls.json")
        bench.reply("ollama/tool-call.json")
        bench.reply("ollama/text.json")

        val first = adapter.complete(request(user("Open the camera and the clock.")), ollama)

        assertEquals(listOf("ollama-call-0", "ollama-call-1"), first.message.toolCalls.map { it.id })
        val history = arrayOf(
            user("Open the camera and the clock."),
            first.message,
            ChatMessage.ToolResult("ollama-call-0", "open_app", "Opened."),
            ChatMessage.ToolResult("ollama-call-1", "open_app", "Not run: Kestrel runs one action per step", failed = true),
        )
        val second = adapter.complete(request(*history), ollama)
        // Ollama has no tool choice, so NONE leaves out the tools.
        adapter.complete(request(*history) { copy(toolChoice = ToolChoice.NONE) }, ollama)

        // A made-up ID is numbered after the highest one in the history.
        assertEquals("ollama-call-2", second.message.toolCalls.single().id)
        bench.take()
        val messages = bench.body(bench.take())["messages"]!!.jsonArray
        assertEquals(5, messages.size)
        assertEquals(json("""{"role":"tool","tool_name":"open_app","content":"Opened."}"""), messages[3])
        assertEquals(json("""{"role":"tool","tool_name":"open_app","content":"Not run: Kestrel runs one action per step"}"""), messages[4])
        assertNull(bench.body(bench.take())["tools"])
    }

    @Test
    fun jsonActionsPassTheSchemaAsFormatAndAReplyWithoutCountsIsEstimated() = runBlocking {
        bench.reply("ollama/no-usage.json")
        val schema = obj("""{"type":"object","properties":{"tool":{"type":"string"},"arguments":{"type":"object"}},"required":["tool","arguments"]}""")
        val cloud = target("ollama-cloud", RequestStyle.OLLAMA, "https://ollama.com", ModelEntry("gpt-oss:120b", "gpt-oss 120B", contextWindow = 131_072))

        val response = adapter.complete(request(user("Hi"), tools = emptyList()) { copy(jsonSchema = schema) }, cloud)

        assertTrue(response.usage.estimated)
        val body = bench.body(bench.take())
        assertEquals(schema, body["format"])
        assertNull(body["tools"])
        // Ollama Cloud is not a local server: no truncate and no num_ctx.
        assertNull(body["truncate"])
        assertNull(body["options"]!!.jsonObject["num_ctx"])
    }

    @Test
    fun theStatusErrorsAnUnfinishedReplyAndAMalformedBodyAreMapped() {
        bench.status(401)
        bench.status(401)
        bench.status(429, """{"error":"too many requests"}""", "Retry-After" to "1")
        bench.status(500, """{"error":"model runner has unexpectedly stopped"}""")
        bench.reply("ollama/not-done.json")
        bench.reply("ollama/malformed.json")
        val auth = AdapterHarness.RefreshingAuth()

        assertEquals(ProviderError.Unauthorized, errorOf(adapter, request(user("Hi")), ollama.copy(auth = auth)))
        assertEquals(1, auth.refreshes)
        assertEquals(ProviderError.RateLimited(1_000, null), errorOf(adapter, request(user("Hi")), ollama))
        assertEquals(ProviderError.ServerError(500), errorOf(adapter, request(user("Hi")), ollama))
        assertEquals(ProviderError.Malformed, errorOf(adapter, request(user("Hi")), ollama))
        assertEquals(ProviderError.Malformed, errorOf(adapter, request(user("Hi")), ollama))
    }
}
