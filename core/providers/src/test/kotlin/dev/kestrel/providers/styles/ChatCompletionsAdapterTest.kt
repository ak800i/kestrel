package dev.kestrel.providers.styles

import dev.kestrel.providers.catalog.CompatFlags
import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatRequest
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

    @Test
    fun eachThinkingFormatPutsTheLevelIntoItsOwnFields() = runBlocking {
        val cases = listOf(
            target("openrouter", RequestStyle.CHAT_COMPLETIONS, "https://openrouter.ai/api/v1", ModelEntry("z-ai/glm-5", "GLM 5", reasoning = true)) to
                """{"reasoning":{"effort":"high"}}""",
            target("zai", RequestStyle.CHAT_COMPLETIONS, "https://api.z.ai/api/paas/v4", ModelEntry("glm-5", "GLM 5", reasoning = true)) to
                """{"thinking":{"type":"enabled","clear_thinking":false}}""",
            target(
                "qwen", RequestStyle.CHAT_COMPLETIONS, "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
                ModelEntry("qwen3.6-plus", "Qwen 3.6 Plus", reasoning = true, compat = CompatFlags(thinkingFormat = "qwen")),
            ) to """{"enable_thinking":true}""",
            target(
                "vllm", RequestStyle.CHAT_COMPLETIONS, "http://127.0.0.1:8000/v1",
                ModelEntry("Qwen/Qwen3.6", "Qwen 3.6", reasoning = true, compat = CompatFlags(thinkingFormat = "qwen-chat-template", supportedReasoningEfforts = listOf("low", "medium", "high"))),
            ) to """{"chat_template_kwargs":{"enable_thinking":true,"reasoning_effort":"high"}}""",
            target("together", RequestStyle.CHAT_COMPLETIONS, "https://api.together.xyz/v1", ModelEntry("moonshotai/Kimi-K3", "Kimi K3", reasoning = true)) to
                """{"reasoning":{"enabled":true}}""",
            target(
                "groq", RequestStyle.CHAT_COMPLETIONS, "https://api.groq.com/openai/v1",
                ModelEntry("openai/gpt-oss-120b", "GPT OSS 120B", reasoning = true, compat = CompatFlags(supportedReasoningEfforts = listOf("low", "medium", "high"))),
            ) to """{"reasoning_effort":"high"}""",
        )
        val fields = listOf("reasoning", "reasoning_effort", "thinking", "enable_thinking", "chat_template_kwargs")

        for ((target, expected) in cases) {
            bench.reply("chat-completions/text.sse")
            adapter.complete(request(user("Hi")) { copy(reasoning = ReasoningLevel.HIGH) }, target)
            val body = bench.body(bench.take())
            assertEquals(target.providerId, json(expected), JsonObject(body.filterKeys { it in fields }))
        }
    }

    @Test
    fun withToolsGpt56GetsNoReasoningOnOpenAisAndAzuresEndpoints() = runBlocking {
        repeat(4) { bench.reply("chat-completions/text.sse") }
        val gpt56 = ModelEntry("gpt-5.6-sol", "GPT-5.6 Sol", reasoning = true)
        val azure = target("azure-openai", RequestStyle.CHAT_COMPLETIONS, "https://kestrel.openai.azure.com/openai/v1", gpt56)
        val high: ChatRequest.() -> ChatRequest = { copy(reasoning = ReasoningLevel.HIGH) }

        adapter.complete(request(user("Hi"), block = high), openAi.copy(model = gpt56, sentModelId = gpt56.id))
        adapter.complete(request(user("Hi"), tools = emptyList(), block = high), openAi.copy(model = gpt56, sentModelId = gpt56.id))
        adapter.complete(request(user("Hi"), block = high), azure)
        adapter.complete(request(user("Hi"), block = high), azure.copy(model = ModelEntry("gpt-5.4-mini", "GPT-5.4 mini", reasoning = true), sentModelId = "gpt-5.4-mini"))

        assertEquals(JsonPrimitive("none"), bench.body(bench.take())["reasoning_effort"])
        assertEquals(JsonPrimitive("high"), bench.body(bench.take())["reasoning_effort"])
        assertEquals(JsonPrimitive("none"), bench.body(bench.take())["reasoning_effort"])
        assertNull(bench.body(bench.take())["reasoning_effort"])
    }

    @Test
    fun theCompatFlagsShapeTheMessages() = runBlocking {
        bench.reply("chat-completions/text.sse")
        bench.reply("chat-completions/text.sse")
        val flags = CompatFlags(cacheControlFormat = "anthropic", requiresToolResultName = true, requiresAssistantAfterToolResult = true, requiresStringContent = true)
        val strict = target("vllm", RequestStyle.CHAT_COMPLETIONS, "http://127.0.0.1:8000/v1", ModelEntry("local-model", "Local model", compat = flags))
        val history = request(
            ChatMessage.User(listOf(Part.Text("What is on the screen?"), Part.Image(ImageType.PNG, SCREENSHOT))),
            ChatMessage.Assistant(listOf(AssistantPart.ToolCall("call_1", "open_app", obj("""{"name":"Camera"}""")))),
            ChatMessage.ToolResult("call_1", "open_app", "Opened."),
            user("Next."),
        )

        adapter.complete(history, strict)
        adapter.complete(history, strict.copy(model = strict.model.copy(compat = CompatFlags(supportsTools = false))))

        assertEquals(
            json(
                """[{"role":"system","content":[{"type":"text","text":"You are Kestrel.","cache_control":{"type":"ephemeral"}}]},
                {"role":"user","content":"What is on the screen?"},
                {"role":"assistant","content":"","tool_calls":[{"id":"call_1","type":"function","function":{"name":"open_app","arguments":"{\"name\":\"Camera\"}"}}]},
                {"role":"tool","tool_call_id":"call_1","name":"open_app","content":"Opened."},
                {"role":"assistant","content":"OK."},
                {"role":"user","content":"Next."}]""",
            ),
            bench.body(bench.take())["messages"],
        )
        // A model without tools gets the history as text.
        val withoutTools = bench.body(bench.take())
        assertNull(withoutTools["tools"])
        assertEquals(
            json(
                """[{"role":"system","content":"You are Kestrel."},
                {"role":"user","content":[{"type":"text","text":"What is on the screen?"},{"type":"image_url","image_url":{"url":"data:image/png;base64,iVBORw0KGgo="}}]},
                {"role":"assistant","content":"[tool call id=call_1 name=open_app] {\"name\":\"Camera\"}"},
                {"role":"user","content":"[tool result id=call_1 name=open_app] Opened."},
                {"role":"user","content":"Next."}]""",
            ),
            withoutTools["messages"],
        )
    }

    @Test
    fun madeUpCallIdsComeAfterThoseOfTheHistoryAndTheProvidersIdsAreKept() = runBlocking {
        bench.status(200, """{"choices":[{"index":0,"message":{"role":"assistant","tool_calls":[{"type":"function","function":{"name":"open_app","arguments":"{\"name\":\"Clock\"}"}},{"type":"function","function":{"name":"open_app","arguments":"{\"name\":\"Alarm\"}"}}]},"finish_reason":"tool_calls"}]}""")
        bench.status(200, """{"choices":[{"index":0,"message":{"role":"assistant","tool_calls":[{"index":0,"id":"call_x","type":"function","function":{"name":"open_app","arguments":"{\"name\":\"Clock\"}"}},{"index":1,"id":"call_x","type":"function","function":{"name":"open_app","arguments":"{\"name\":\"Alarm\"}"}}]},"finish_reason":"tool_calls"}]}""")
        val history = request(
            user("Open the camera."),
            ChatMessage.Assistant(listOf(AssistantPart.ToolCall("call_4", "open_app", obj("""{"name":"Camera"}""")))),
            ChatMessage.ToolResult("call_4", "open_app", "Opened."),
            user("Now the clock and the alarm."),
        )
        val plain = gptOss.copy(options = StyleOptions(stream = false))

        val madeUp = adapter.complete(history, plain)
        val repeated = adapter.complete(history, plain)

        assertEquals(listOf("call_5", "call_6"), madeUp.message.toolCalls.map { it.id })
        assertEquals(listOf("call_x", "call_5"), repeated.message.toolCalls.map { it.id })
    }

    @Test
    fun aThoughtSignatureIsKeptUnderTheCallsFinalId() = runBlocking {
        bench.status(200, """{"choices":[{"index":0,"message":{"role":"assistant","tool_calls":[{"index":0,"id":"call_x","type":"function","function":{"name":"open_app","arguments":"{\"name\":\"Camera\"}"},"extra_content":{"google":{"thought_signature":"c2lnLWE="}}},{"index":1,"id":"call_x","type":"function","function":{"name":"open_app","arguments":"{\"name\":\"Clock\"}"},"extra_content":{"google":{"thought_signature":"c2lnLWI="}}}]},"finish_reason":"tool_calls"}]}""")
        bench.reply("chat-completions/deepseek-text.sse")
        val plain = gptOss.copy(options = StyleOptions(stream = false))

        val first = adapter.complete(request(user("Open the camera and the clock.")), plain)
        val next = request(
            user("Open the camera and the clock."),
            first.message,
            ChatMessage.ToolResult("call_x", "open_app", "Opened."),
            ChatMessage.ToolResult("call_2", "open_app", "Not run: Kestrel runs one action per step", failed = true),
        )
        adapter.complete(next, gptOss)

        assertEquals(listOf("call_x", "call_2"), first.message.toolCalls.map { it.id })
        val signatures = first.message.parts.filterIsInstance<AssistantPart.Reasoning>().single().state.payload as JsonObject
        assertEquals(json("""{"call_x":"c2lnLWE=","call_2":"c2lnLWI="}"""), signatures["thoughtSignatures"])
        bench.take()
        val replayed = bench.body(bench.take())["messages"]!!.jsonArray[2].jsonObject["tool_calls"]!!.jsonArray[1].jsonObject
        assertEquals(json("""{"google":{"thought_signature":"c2lnLWI="}}"""), replayed["extra_content"])
    }

    @Test
    fun aBlankFinishReasonDoesNotEndTheStream() {
        bench.status(200, "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hel\"},\"finish_reason\":\"\"}]}\n\n", "Content-Type" to "text/event-stream")

        assertEquals(ProviderError.Malformed, errorOf(adapter, request(user("Hi")), gptOss))
    }

    @Test
    fun aStreamThatGathersMoreThan8MiBIsMalformed() {
        val piece = "a".repeat(512 * 1024)
        val events = (1..17).joinToString("") { "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"$piece\"}}]}\n\n" } + "data: [DONE]\n\n"
        bench.status(200, events, "Content-Type" to "text/event-stream")

        assertEquals(ProviderError.Malformed, errorOf(adapter, request(user("Hi")), gptOss))
    }

    @Test
    fun openRoutersReasoningTextGoesBackAsReasoning() = runBlocking {
        bench.reply("chat-completions/text.sse")
        val glm = target("openrouter", RequestStyle.CHAT_COMPLETIONS, "https://openrouter.ai/api/v1", ModelEntry("z-ai/glm-5", "GLM 5", reasoning = true))
        val thought = ReasoningState(RequestStyle.CHAT_COMPLETIONS, "openrouter", "z-ai/glm-5", obj("""{"field":"reasoning_text","text":"Say hello."}"""))

        adapter.complete(request(user("Hi"), ChatMessage.Assistant(listOf(AssistantPart.Reasoning(thought), AssistantPart.Text("Hello."))), user("Again.")), glm)

        val assistant = bench.body(bench.take())["messages"]!!.jsonArray[2].jsonObject
        assertEquals(JsonPrimitive("Say hello."), assistant["reasoning"])
        assertNull(assistant["reasoning_text"])
    }

    @Test
    fun aModelThatTakesNoReasoningEffortGetsNoEffortInAnyFormat() = runBlocking {
        bench.reply("chat-completions/text.sse")
        bench.reply("chat-completions/text.sse")
        val noEffort = CompatFlags(supportsReasoningEffort = false)
        val glm = target("openrouter", RequestStyle.CHAT_COMPLETIONS, "https://openrouter.ai/api/v1", ModelEntry("z-ai/glm-5", "GLM 5", reasoning = true, compat = noEffort))
        val qwen = target(
            "vllm", RequestStyle.CHAT_COMPLETIONS, "http://127.0.0.1:8000/v1",
            ModelEntry("Qwen/Qwen3.6", "Qwen 3.6", reasoning = true, compat = noEffort.copy(thinkingFormat = "qwen-chat-template", supportedReasoningEfforts = listOf("low", "medium", "high"))),
        )

        adapter.complete(request(user("Hi")) { copy(reasoning = ReasoningLevel.HIGH) }, glm)
        adapter.complete(request(user("Hi")) { copy(reasoning = ReasoningLevel.HIGH) }, qwen)

        assertEquals(json("""{"enabled":true}"""), bench.body(bench.take())["reasoning"])
        assertEquals(json("""{"enable_thinking":true}"""), bench.body(bench.take())["chat_template_kwargs"])
    }

    @Test
    fun callPiecesCountTowardsTheGatherLimitWithTheirIdsAndNames() {
        val piece = "a".repeat(512 * 1024)
        val events = (1..17).joinToString("") {
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":$it,\"id\":\"$piece$it\",\"function\":{\"name\":\"open_app\"}}]}}]}\n\n"
        } + "data: [DONE]\n\n"
        bench.status(200, events, "Content-Type" to "text/event-stream")

        assertEquals(ProviderError.Malformed, errorOf(adapter, request(user("Hi")), gptOss))
    }

    @Test
    fun theUsageAliasesOfOpenClawAreRead() = runBlocking {
        val events = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hi\"},\"finish_reason\":\"stop\"}]}\n\n" +
            "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":1000,\"completion_tokens\":10,\"cached_tokens\":600,\"reasoning_tokens\":4}}\n\n" +
            "data: [DONE]\n\n"
        bench.status(200, events, "Content-Type" to "text/event-stream")

        assertEquals(Usage(input = 400, output = 10, cacheRead = 600, reasoning = 4), adapter.complete(request(user("Hi")), gptOss).usage)
    }
}
