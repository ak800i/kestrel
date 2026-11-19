package dev.kestrel.providers.styles

import dev.kestrel.providers.catalog.CompatFlags
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
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.styles.AdapterHarness.Companion.OPEN_APP
import dev.kestrel.providers.styles.AdapterHarness.Companion.SCREENSHOT
import dev.kestrel.providers.styles.AdapterHarness.Companion.errorOf
import dev.kestrel.providers.styles.AdapterHarness.Companion.json
import dev.kestrel.providers.styles.AdapterHarness.Companion.obj
import dev.kestrel.providers.styles.AdapterHarness.Companion.request
import dev.kestrel.providers.styles.AdapterHarness.Companion.target
import dev.kestrel.providers.styles.AdapterHarness.Companion.user
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCompletionsAdapterTest {
    private val bench = AdapterHarness()
    private val adapter = ChatCompletionsAdapter(bench.http)

    private val gpt = ModelEntry("gpt-5.5", "GPT-5.5", reasoning = true)
    private val openAi = target("openai", RequestStyle.CHAT_COMPLETIONS, "https://api.openai.com/v1", gpt)
    private val gptOss = target("groq", RequestStyle.CHAT_COMPLETIONS, "https://api.groq.com/openai/v1", ModelEntry("openai/gpt-oss-120b", "GPT OSS 120B"))
    private val deepSeekCompat = CompatFlags(maxTokensField = "max_tokens", supportsReasoningEffort = true, requiresReasoningContentOnAssistantMessages = true, supportsUsageInStreaming = true)
    private fun deepSeek(id: String) = target(
        "deepseek", RequestStyle.CHAT_COMPLETIONS, "https://api.deepseek.com",
        ModelEntry(id, id, reasoning = true, compat = deepSeekCompat, thinkingLevelMap = mapOf("xhigh" to "high", "max" to "max")),
    )

    @After
    fun close() = bench.close()

    @Test
    fun aTextAnswerIsGatheredWithItsUsageAndTheRequestFollowsTheCompatFlags() = runBlocking {
        // The body has no reasoning_effort: OpenAI refuses it for GPT-5.5 with tools (OpenClaw's applyOpenAICompletionsToolReasoning).
        bench.reply("chat-completions/text.sse")

        val response = adapter.complete(request(user("Hi")) { copy(reasoning = ReasoningLevel.LOW) }, openAi)

        assertEquals(listOf(AssistantPart.Text("Hello, owner.")), response.message.parts)
        assertEquals(StopReason.END, response.stop)
        assertEquals(Usage(input = 100, output = 40, cacheRead = 800, cacheWrite = 300, reasoning = 25), response.usage)
        assertEquals("gpt-5.5-2026-08-01", response.modelId)
        assertEquals(listOf("https://api.openai.com/v1/chat/completions".toHttpUrl()), bench.calls.requests)
        val recorded = bench.take()
        assertEquals("text/event-stream", recorded.headers["accept"])
        bench.assertBody("chat-completions/text.request.json", recorded)
    }

    @Test
    fun deepSeeksReasoningGoesBackWithTheToolCallAfterAnEscalation() = runBlocking {
        bench.reply("chat-completions/deepseek-tool-call.sse")
        bench.reply("chat-completions/deepseek-text.sse")
        val earlier = listOf(user("Hi"), ChatMessage.Assistant(listOf(AssistantPart.Text("Hello."))), user("Open the camera."))

        val first = adapter.complete(request(*earlier.toTypedArray()) { copy(reasoning = ReasoningLevel.LOW) }, deepSeek("deepseek-v4-flash"))

        assertEquals(StopReason.TOOL_CALL, first.stop)
        val state = ReasoningState(RequestStyle.CHAT_COMPLETIONS, "deepseek", "deepseek-v4-flash", obj("""{"field":"reasoning_content","text":"The owner wants the camera."}"""))
        assertEquals(
            listOf(AssistantPart.Reasoning(state), AssistantPart.ToolCall("call_cam", "open_app", obj("""{"name":"Camera"}"""))),
            first.message.parts,
        )
        val next = request(*(earlier + first.message + ChatMessage.ToolResult("call_cam", "open_app", "Opened.")).toTypedArray()) {
            copy(reasoning = ReasoningLevel.LOW)
        }
        val second = adapter.complete(next, deepSeek("deepseek-v4-pro"))

        assertEquals(listOf(AssistantPart.Text("The camera is open.")), second.message.parts)
        bench.take()
        bench.assertBody("chat-completions/deepseek-escalation.request.json", bench.take())

        // OpenClaw's shouldPreserveOpenRouterReasoningReplay: OpenRouter's Claude routes get no plain reasoning back.
        bench.reply("chat-completions/text.sse")
        val claude = target("openrouter", RequestStyle.CHAT_COMPLETIONS, "https://openrouter.ai/api/v1", ModelEntry("anthropic/claude-sonnet-5", "Claude Sonnet 5", reasoning = true))
        val thought = ReasoningState(RequestStyle.CHAT_COMPLETIONS, "openrouter", "anthropic/claude-sonnet-5", obj("""{"field":"reasoning","text":"Say hello."}"""))
        adapter.complete(request(user("Hi"), ChatMessage.Assistant(listOf(AssistantPart.Reasoning(thought), AssistantPart.Text("Hello."))), user("Again.")), claude)
        assertNull(bench.body(bench.take())["messages"]!!.jsonArray[2].jsonObject["reasoning"])
    }

    @Test
    fun aReplyWithTwoToolCallsKeepsBothInOrderAndTheNextRequestAnswersBoth() = runBlocking {
        bench.reply("chat-completions/two-calls-without-index.sse")
        bench.reply("chat-completions/repeated-ids.sse")
        bench.reply("chat-completions/two-calls.sse")
        bench.reply("chat-completions/deepseek-text.sse")

        // A server that sends no `index`: a new `id` starts a new call. Its signatures sit in `function` and on the piece.
        val withoutIndex = adapter.complete(request(user("Open the camera and the clock.")), gptOss)
        // A server that repeats an ID: the second call gets a fresh one, so that each result has its own.
        val repeated = adapter.complete(request(user("Open the camera and the clock.")), gptOss)
        val first = adapter.complete(request(user("Open the camera and the clock.")), gptOss)

        for (reply in listOf(withoutIndex, first)) {
            assertEquals(listOf("call_a", "call_b"), reply.message.toolCalls.map { it.id })
            assertEquals(listOf(obj("""{"name":"Camera"}"""), obj("""{"name":"Clock"}""")), reply.message.toolCalls.map { it.arguments })
        }
        val signatures = withoutIndex.message.parts.filterIsInstance<AssistantPart.Reasoning>().single().state.payload as JsonObject
        assertEquals(json("""{"call_a":"c2lnLWE=","call_b":"c2lnLWI="}"""), signatures["thoughtSignatures"])
        assertEquals(listOf("call_x", "call_2"), repeated.message.toolCalls.map { it.id })
        // Some compatible servers put the usage on the choice.
        assertEquals(Usage(input = 300, output = 20), first.usage)
        val next = request(
            user("Open the camera and the clock."),
            first.message,
            ChatMessage.ToolResult("call_a", "open_app", "Opened."),
            ChatMessage.ToolResult("call_b", "open_app", "Not run: Kestrel runs one action per step", failed = true),
        )
        adapter.complete(next, gptOss)

        bench.take()
        bench.take()
        bench.take()
        val messages = bench.body(bench.take())["messages"]!!.jsonArray
        assertEquals(listOf("system", "user", "assistant", "tool", "tool"), messages.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertEquals(listOf("call_a", "call_b"), messages[2].jsonObject["tool_calls"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals(JsonPrimitive("call_b"), messages[4].jsonObject["tool_call_id"])

        // Without streaming, two calls without `index` and `id` in one body stay two calls.
        bench.status(200, """{"model":"openai/gpt-oss-120b","choices":[{"index":0,"message":{"role":"assistant","tool_calls":[{"type":"function","function":{"name":"open_app","arguments":"{\"name\":\"Camera\"}"}},{"type":"function","function":{"name":"open_app","arguments":"{\"name\":\"Clock\"}"}}]},"finish_reason":"tool_calls"}]}""")
        val plain = adapter.complete(request(user("Open the camera and the clock.")), gptOss.copy(options = StyleOptions(stream = false)))
        assertEquals(listOf(obj("""{"name":"Camera"}"""), obj("""{"name":"Clock"}""")), plain.message.toolCalls.map { it.arguments })
    }

    @Test
    fun anImageGoesAsADataUrlBesideTheText() = runBlocking {
        bench.reply("chat-completions/text.sse")
        val screen = ChatMessage.User(listOf(Part.Text("What is on the screen?"), Part.Image(ImageType.PNG, SCREENSHOT)))

        adapter.complete(request(screen), openAi)

        val content = bench.body(bench.take())["messages"]!!.jsonArray[1].jsonObject["content"]
        assertEquals(
            json("""[{"type":"text","text":"What is on the screen?"},{"type":"image_url","image_url":{"url":"data:image/png;base64,iVBORw0KGgo="}}]"""),
            content,
        )
    }

    @Test
    fun copilotsGeminiKeepsItsThoughtSignatureAndAStreamWithoutUsageIsEstimated() = runBlocking {
        bench.reply("chat-completions/copilot-gemini.sse")
        bench.reply("chat-completions/deepseek-text.sse")
        val copilot = target(
            "github-copilot", RequestStyle.CHAT_COMPLETIONS, "https://api.individual.githubcopilot.com",
            ModelEntry("gemini-3.8-flash", "Gemini 3.8 Flash", reasoning = true),
            options = StyleOptions(compat = CompatFlags(maxTokensField = "max_tokens", supportsStore = false, supportsDeveloperRole = false, supportsUsageInStreaming = false)),
        )

        val first = adapter.complete(request(user("What time is it?")), copilot)

        assertTrue(first.usage.estimated)
        assertTrue(first.usage.input > 0)
        assertEquals(listOf("call_g1"), first.message.toolCalls.map { it.id })
        adapter.complete(request(user("What time is it?"), first.message, ChatMessage.ToolResult("call_g1", "open_app", "Opened.")), copilot)

        val firstBody = bench.body(bench.take())
        assertNull(firstBody["stream_options"])
        assertNull(firstBody["store"])
        assertEquals(JsonPrimitive(256), firstBody["max_tokens"])
        val call = bench.body(bench.take())["messages"]!!.jsonArray[2].jsonObject["tool_calls"]!!.jsonArray[0].jsonObject
        assertEquals(json("""{"google":{"thought_signature":"c2lnbmF0dXJlLW9uZQ=="}}"""), call["extra_content"])
    }

    @Test
    fun withoutToolsTheHistoryIsTextAndTheActionSchemaGoesIntoTheResponseFormat() = runBlocking {
        bench.reply("chat-completions/text.sse")
        val call = AssistantPart.ToolCall("call_1", "open_app", obj("""{"name":"Camera"}"""))
        val schema = obj("""{"type":"object","properties":{"tool":{"type":"string","enum":["open_app"]},"arguments":{"type":"object"}},"required":["tool","arguments"]}""")
        val jsonRequest = request(
            user("Open the camera."),
            ChatMessage.Assistant(listOf(call)),
            ChatMessage.ToolResult("call_1", "open_app", "Opened."),
            tools = emptyList(),
        ) { copy(jsonSchema = schema) }

        adapter.complete(jsonRequest, openAi)

        val body = bench.body(bench.take())
        assertNull(body["tools"])
        assertNull(body["tool_choice"])
        val messages = body["messages"]!!.jsonArray
        assertEquals(json("""{"role":"assistant","content":"[tool call id=call_1 name=open_app] {\"name\":\"Camera\"}"}"""), messages[2])
        assertEquals(json("""{"role":"user","content":"[tool result id=call_1 name=open_app] Opened."}"""), messages[3])
        assertEquals(json("""{"type":"json_schema","json_schema":{"name":"kestrel_action","schema":$schema}}"""), body["response_format"])
    }

    @Test
    fun aCallThatKestrelCannotUseIsAnInvalidToolCall() = runBlocking {
        bench.reply("chat-completions/invalid-calls.sse")
        bench.reply("chat-completions/truncated-call.sse")

        val unknown = adapter.complete(request(user("Close the camera.")), gptOss)
        val truncated = adapter.complete(request(user("Open the camera.")), gptOss)

        assertEquals(InvalidKind.UNKNOWN_TOOL, unknown.invalidToolCall?.kind)
        assertTrue(unknown.message.toolCalls.isEmpty())
        // The finish reason `tool_call` (singular) is a tool call, as OpenClaw reads it.
        assertEquals(StopReason.TOOL_CALL, unknown.stop)
        assertEquals(InvalidKind.ARGUMENTS_NOT_OBJECT, truncated.invalidToolCall?.kind)
        assertEquals(StopReason.MAX_TOKENS, truncated.stop)
        assertTrue(truncated.message.toolCalls.isEmpty())
    }

    @Test
    fun a401IsRefreshedOnceAndSentAgain() = runBlocking {
        bench.status(401)
        bench.reply("chat-completions/text.sse")
        val auth = AdapterHarness.RefreshingAuth()

        adapter.complete(request(user("Hi")), openAi.copy(auth = auth))

        assertEquals(1, auth.refreshes)
        assertEquals("Bearer old-token", bench.take().headers["Authorization"])
        assertEquals("Bearer new-token", bench.take().headers["Authorization"])
    }

    @Test
    fun a429KeepsItsRetryAfterAndCodeAndA500IsAServerError() {
        bench.status(429, """{"error":{"code":"rate_limit_exceeded","message":"Slow down."}}""", "Retry-After" to "2")
        bench.status(500, """{"error":{"message":"oops"}}""")

        assertEquals(ProviderError.RateLimited(2_000, "rate_limit_exceeded"), errorOf(adapter, request(user("Hi")), openAi))
        assertEquals(ProviderError.ServerError(500), errorOf(adapter, request(user("Hi")), openAi))
    }

    @Test
    fun anErrorInsideTheStreamIsMappedLikeAnHttpError() {
        bench.reply("chat-completions/stream-error.sse")

        assertEquals(ProviderError.ServerError(502), errorOf(adapter, request(user("Hi")), gptOss))
    }

    @Test
    fun aMalformedChunkAndAStreamThatEndsEarlyAreMalformed() {
        bench.reply("chat-completions/malformed.sse")
        bench.reply("chat-completions/ends-early.sse")

        assertEquals(ProviderError.Malformed, errorOf(adapter, request(user("Hi")), openAi))
        assertEquals(ProviderError.Malformed, errorOf(adapter, request(user("Hi")), openAi))
    }

    @Test
    fun theReasoningListOfOpenClawMatchesTheLastSegmentAndTierSuffixes() {
        assertTrue(ChatCompletionsAdapter.replaysReasoning("moonshotai/kimi-k3-free"))
        assertTrue(ChatCompletionsAdapter.replaysReasoning("deepseek-v4-pro:cloud"))
        assertFalse(ChatCompletionsAdapter.replaysReasoning("gpt-5.5"))
    }
}
