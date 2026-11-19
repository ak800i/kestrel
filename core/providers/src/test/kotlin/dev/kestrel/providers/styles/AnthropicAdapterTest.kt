package dev.kestrel.providers.styles

import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ImageType
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.chat.ReasoningLevel
import dev.kestrel.providers.chat.ReasoningState
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.chat.StopReason
import dev.kestrel.providers.chat.StyleOptions
import dev.kestrel.providers.chat.ToolChoice
import dev.kestrel.providers.chat.Usage
import dev.kestrel.providers.http.ApiKeyAuth
import dev.kestrel.providers.http.KeyHeader
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
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
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicAdapterTest {
    private val bench = AdapterHarness()
    private val adapter = AnthropicAdapter(bench.http)

    private val sonnet = ModelEntry("claude-sonnet-5", "Claude Sonnet 5", reasoning = true, thinkingLevelMap = mapOf("xhigh" to "xhigh", "max" to "max"))
    private val anthropic = target("anthropic", RequestStyle.ANTHROPIC, "https://api.anthropic.com", sonnet, ApiKeyAuth("test-key", KeyHeader.X_API_KEY))

    @After
    fun close() = bench.close()

    @Test
    fun aTextAnswerIsGatheredWithItsUsageAndTheRequestHasThinkingAndCacheMarkers() = runBlocking {
        bench.reply("anthropic/text.sse")

        val response = adapter.complete(request(user("Hi")) { copy(reasoning = ReasoningLevel.LOW) }, anthropic)

        assertEquals(listOf(AssistantPart.Text("Hello, owner.")), response.message.parts)
        assertEquals(StopReason.END, response.stop)
        assertEquals(Usage(input = 50, output = 42, cacheRead = 2000, cacheWrite = 1000), response.usage)
        assertEquals(listOf("https://api.anthropic.com/v1/messages".toHttpUrl()), bench.calls.requests)
        val recorded = bench.take()
        assertEquals("2023-06-01", recorded.headers["anthropic-version"])
        assertEquals("test-key", recorded.headers["x-api-key"])
        bench.assertBody("anthropic/text.request.json", recorded)
    }

    @Test
    fun thinkingBlocksGoBackUnchangedAndInOrderBeforeTheToolUse() = runBlocking {
        bench.reply("anthropic/thinking-tool-call.sse")
        bench.reply("anthropic/text.sse")

        val first = adapter.complete(request(user("Open the camera.")) { copy(reasoning = ReasoningLevel.LOW) }, anthropic)

        val thinking = obj("""{"type":"thinking","thinking":"The owner wants the camera.","signature":"EosnCkYICxIMMb3LzNrMu"}""")
        val redacted = obj("""{"type":"redacted_thinking","data":"EmwKAhgBEgy3va3pzix"}""")
        assertEquals(
            listOf(
                AssistantPart.Reasoning(ReasoningState(RequestStyle.ANTHROPIC, "anthropic", "claude-sonnet-5", thinking)),
                AssistantPart.Reasoning(ReasoningState(RequestStyle.ANTHROPIC, "anthropic", "claude-sonnet-5", redacted)),
                AssistantPart.ToolCall("toolu_01", "open_app", obj("""{"name":"Camera"}""")),
            ),
            first.message.parts,
        )
        assertEquals(StopReason.TOOL_CALL, first.stop)
        adapter.complete(request(user("Open the camera."), first.message, ChatMessage.ToolResult("toolu_01", "open_app", "Opened.")), anthropic)

        bench.take()
        val messages = bench.body(bench.take())["messages"]!!.jsonArray
        assertEquals(
            json("""[$thinking,$redacted,{"type":"tool_use","id":"toolu_01","name":"open_app","input":{"name":"Camera"}}]"""),
            messages[1].jsonObject["content"],
        )
        assertEquals(json("""[{"type":"tool_result","tool_use_id":"toolu_01","content":"Opened."}]"""), messages[2].jsonObject["content"])
    }

    @Test
    fun twoToolResultsAndTheNextScreenBecomeOneUserTurn() = runBlocking {
        bench.reply("anthropic/two-calls.sse")
        bench.reply("anthropic/text.sse")

        val first = adapter.complete(request(user("Open the camera and the clock.")), anthropic)

        assertEquals(listOf("toolu_a", "toolu_b"), first.message.toolCalls.map { it.id })
        val next = request(
            user("Open the camera and the clock."),
            first.message,
            ChatMessage.ToolResult("toolu_a", "open_app", "Opened."),
            ChatMessage.ToolResult("toolu_b", "open_app", "Not run: Kestrel runs one action per step", failed = true),
            ChatMessage.User(listOf(Part.Text("The screen now."), Part.Text(" \n"), Part.Image(ImageType.PNG, SCREENSHOT))),
        ) { copy(system = " ") }
        adapter.complete(next, anthropic)

        bench.take()
        val body = bench.body(bench.take())
        // Anthropic refuses blank text blocks, so the blank system prompt and the blank text are left out.
        assertNull(body["system"])
        val messages = body["messages"]!!.jsonArray
        assertEquals(3, messages.size)
        assertEquals(
            json(
                """[{"type":"tool_result","tool_use_id":"toolu_a","content":"Opened."},
                {"type":"tool_result","tool_use_id":"toolu_b","content":"Not run: Kestrel runs one action per step","is_error":true},
                {"type":"text","text":"The screen now."},
                {"type":"image","source":{"type":"base64","media_type":"image/png","data":"iVBORw0KGgo="}}]""",
            ),
            messages[2].jsonObject["content"],
        )
    }

    @Test
    fun aRequiredToolBecomesAutoAndNoTemperatureIsSentWhileTheModelThinks() = runBlocking {
        repeat(5) { bench.reply("anthropic/text.sse") }
        val sonnet46 = anthropic.copy(model = ModelEntry("claude-sonnet-4-6", "Claude Sonnet 4.6", reasoning = true), sentModelId = "claude-sonnet-4-6")
        val opus48 = anthropic.copy(model = ModelEntry("claude-opus-4-8", "Claude Opus 4.8", reasoning = true), sentModelId = "claude-opus-4-8")
        val haiku55 = anthropic.copy(model = ModelEntry("claude-haiku-5.5", "Claude Haiku 5.5", reasoning = true), sentModelId = "claude-haiku-5.5")

        adapter.complete(request(user("Hi")) { copy(toolChoice = ToolChoice.REQUIRED, temperature = 0.2) }, sonnet46)
        adapter.complete(request(user("Hi")) { copy(toolChoice = ToolChoice.REQUIRED, temperature = 0.2, reasoning = ReasoningLevel.HIGH) }, sonnet46)
        // Claude Sonnet 5 thinks by default, so also a request that does not ask for thinking gets `auto` and no temperature.
        adapter.complete(request(user("Hi")) { copy(toolChoice = ToolChoice.REQUIRED, temperature = 0.2) }, anthropic)
        adapter.complete(request(user("Hi")) { copy(temperature = 0.2) }, opus48)
        // OpenClaw keeps a forced tool choice for Claude Haiku 5.5, which Anthropic accepts while it thinks.
        adapter.complete(request(user("Hi")) { copy(toolChoice = ToolChoice.REQUIRED, temperature = 0.2) }, haiku55)

        val plain = bench.body(bench.take())
        assertEquals(json("""{"type":"any","disable_parallel_tool_use":true}"""), plain["tool_choice"])
        assertEquals(json("0.2"), plain["temperature"])
        val thinking = bench.body(bench.take())
        assertEquals(json("""{"type":"auto","disable_parallel_tool_use":true}"""), thinking["tool_choice"])
        assertNull(thinking["temperature"])
        val byDefault = bench.body(bench.take())
        assertEquals(json("""{"type":"auto","disable_parallel_tool_use":true}"""), byDefault["tool_choice"])
        assertNull(byDefault["thinking"])
        assertNull(byDefault["temperature"])
        assertNull(bench.body(bench.take())["temperature"])
        val haiku = bench.body(bench.take())
        assertEquals(json("""{"type":"any","disable_parallel_tool_use":true}"""), haiku["tool_choice"])
        assertNull(haiku["temperature"])
    }

    @Test
    fun copilotStripsThinkingRewritesIdsAndUsesItsMessagesPath() = runBlocking {
        bench.reply("anthropic/copilot-tool-call.sse")
        bench.reply("anthropic/text.sse")
        val copilot = target(
            "github-copilot", RequestStyle.ANTHROPIC, "https://api.individual.githubcopilot.com",
            ModelEntry("claude-haiku-4.5", "Claude Haiku 4.5", reasoning = true),
            options = StyleOptions(stripThinking = true, sanitizeToolCallIds = true, messagesPath = "/messages"),
        )

        val first = adapter.complete(request(user("Open the camera.")) { copy(reasoning = ReasoningLevel.LOW) }, copilot)

        assertEquals(listOf(AssistantPart.ToolCall("toolu_c", "open_app", obj("""{"name":"Camera"}"""))), first.message.parts)
        val history = request(
            user("Open the camera and the clock."),
            ChatMessage.Assistant(
                listOf(
                    AssistantPart.ToolCall("call.1:x", "open_app", obj("""{"name":"Camera"}""")),
                    AssistantPart.ToolCall("call:1.x", "open_app", obj("""{"name":"Clock"}""")),
                ),
            ),
            ChatMessage.ToolResult("call.1:x", "open_app", "Opened."),
            ChatMessage.ToolResult("call:1.x", "open_app", "Not run: Kestrel runs one action per step", failed = true),
        )
        adapter.complete(history, copilot)

        assertEquals("https://api.individual.githubcopilot.com/messages".toHttpUrl(), bench.calls.requests.first())
        val firstBody = bench.body(bench.take())
        assertNull(firstBody["thinking"])
        assertNull(firstBody["output_config"])
        val messages = bench.body(bench.take())["messages"]!!.jsonArray
        // Two IDs that become the same get `_2`, as in OpenClaw's normalizeCopilotAnthropicToolIds.
        val calls = messages[1].jsonObject["content"]!!.jsonArray.map { it.jsonObject["id"] }
        val results = messages[2].jsonObject["content"]!!.jsonArray.map { it.jsonObject["tool_use_id"] }
        assertEquals(listOf(json(""""call_1_x""""), json(""""call_1_x_2"""")), calls)
        assertEquals(calls, results)
    }

    @Test
    fun copilotSendsAutoAndNoTemperatureToAClaudeModelThatThinksByDefaultButStillStripsItsThinking() = runBlocking {
        bench.reply("anthropic/copilot-tool-call.sse")
        bench.reply("anthropic/text.sse")
        val copilot = target(
            "github-copilot", RequestStyle.ANTHROPIC, "https://api.individual.githubcopilot.com",
            ModelEntry("claude-sonnet-5", "Claude Sonnet 5", reasoning = true),
            options = StyleOptions(stripThinking = true, sanitizeToolCallIds = true, messagesPath = "/messages"),
        )

        val first = adapter.complete(request(user("Open the camera.")) { copy(toolChoice = ToolChoice.REQUIRED, temperature = 0.2) }, copilot)
        adapter.complete(request(user("Open the camera."), first.message, ChatMessage.ToolResult("toolu_c", "open_app", "Opened.")), copilot)

        // OpenClaw's Copilot replay policy strips replayed thinking for every Claude model, Claude 5 included.
        assertEquals(listOf(AssistantPart.ToolCall("toolu_c", "open_app", obj("""{"name":"Camera"}"""))), first.message.parts)
        val firstBody = bench.body(bench.take())
        assertNull(firstBody["thinking"])
        assertNull(firstBody["temperature"])
        assertEquals(json("""{"type":"auto","disable_parallel_tool_use":true}"""), firstBody["tool_choice"])
        val replayed = bench.body(bench.take())["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
        assertEquals(1, replayed.size)
        assertEquals(json(""""tool_use""""), replayed[0].jsonObject["type"])
    }

    @Test
    fun theVertexFormSendsTheVersionInTheBodyAndDoesNotStream() = runBlocking {
        bench.reply("anthropic/vertex-message.json")
        val vertex = target("google-vertex", RequestStyle.VERTEX, "https://aiplatform.googleapis.com/v1/projects/p/locations/global", ModelEntry("claude-sonnet-4-6", "Claude Sonnet 4.6", style = RequestStyle.ANTHROPIC))
        val url = "https://aiplatform.googleapis.com/v1/projects/p/locations/global/publishers/anthropic/models/claude-sonnet-4-6:rawPredict".toHttpUrl()

        val response = adapter.complete(request(user("Hi"), tools = emptyList()), vertex, url, vertex = true, logPath = "/v1/projects/{project}/x")

        assertEquals(listOf(AssistantPart.Text("Hello from Vertex.")), response.message.parts)
        assertEquals(Usage(input = 30, output = 6), response.usage)
        val recorded = bench.take()
        assertNull(recorded.headers["anthropic-version"])
        assertEquals("application/json", recorded.headers["accept"])
        val body = bench.body(recorded)
        assertEquals(json(""""vertex-2023-10-16""""), body["anthropic_version"])
        assertNull(body["model"])
        assertEquals(json("false"), body["stream"])
    }

    @Test
    fun aStreamWithoutUsageIsEstimatedAndUsageThatComesOnlyAtTheEndIsRead() = runBlocking {
        bench.reply("anthropic/no-usage.sse")
        bench.reply("anthropic/late-usage.sse")

        val response = adapter.complete(request(user("Hi")), anthropic)
        // late-usage.sse ends with a proxy's `data: [DONE]` after message_stop.
        val late = adapter.complete(request(user("Hi")), anthropic)

        assertTrue(response.usage.estimated)
        assertEquals("MiniMax-M3", response.modelId)
        // OpenClaw's mapAnthropicStopReason treats `sensitive` like `refusal`.
        assertEquals(StopReason.FILTERED, response.stop)
        assertEquals(Usage(input = 120, output = 9, cacheRead = 800), late.usage)
    }

    @Test
    fun theStatusErrorsAndTheStreamsErrorEventAreMapped() {
        bench.status(401)
        bench.status(401)
        bench.status(429, """{"type":"error","error":{"type":"rate_limit_error","message":"Slow down."}}""", "Retry-After" to "5")
        bench.status(500)
        bench.reply("anthropic/overloaded.sse")
        val auth = AdapterHarness.RefreshingAuth()

        assertEquals(ProviderError.Unauthorized, errorOf(adapter, request(user("Hi")), anthropic.copy(auth = auth)))
        assertEquals(1, auth.refreshes)
        assertEquals(ProviderError.RateLimited(5_000, "rate_limit_error"), errorOf(adapter, request(user("Hi")), anthropic))
        assertEquals(ProviderError.ServerError(500), errorOf(adapter, request(user("Hi")), anthropic))
        assertEquals(ProviderError.ServerError(529), errorOf(adapter, request(user("Hi")), anthropic))
    }

    @Test
    fun aMalformedEventAStreamWithoutMessageStopAndAMessageWithoutContentAreMalformed() {
        bench.reply("anthropic/malformed.sse")
        bench.reply("anthropic/ends-early.sse")
        bench.status(200, """{"type":"message","role":"assistant","model":"claude-sonnet-4-6"}""")
        val vertex = target("google-vertex", RequestStyle.VERTEX, "https://aiplatform.googleapis.com/v1/projects/p/locations/global", ModelEntry("claude-sonnet-4-6", "Claude Sonnet 4.6", style = RequestStyle.ANTHROPIC))
        val url = "https://aiplatform.googleapis.com/v1/projects/p/locations/global/publishers/anthropic/models/claude-sonnet-4-6:rawPredict".toHttpUrl()

        assertEquals(ProviderError.Malformed, errorOf(adapter, request(user("Hi")), anthropic))
        assertEquals(ProviderError.Malformed, errorOf(adapter, request(user("Hi")), anthropic))
        val error = try {
            runBlocking { adapter.complete(request(user("Hi")), vertex, url, vertex = true, logPath = null) }
            null
        } catch (e: ProviderException) {
            e.error
        }
        assertEquals(ProviderError.Malformed, error)
    }

    @Test
    fun openClawsClaudeRulesNameTheAdaptiveModels() {
        assertTrue(AnthropicModels.adaptive("claude-sonnet-4-6"))
        assertTrue(AnthropicModels.adaptive("anthropic.claude-opus-5"))
        assertTrue(AnthropicModels.adaptive("claude-haiku-5.5"))
        assertFalse(AnthropicModels.adaptive("claude-sonnet-4-5"))
        assertFalse(AnthropicModels.adaptive("claude-haiku-4.5"))
        assertFalse(AnthropicModels.adaptive("MiniMax-M3"))
        for (id in listOf("claude-sonnet-5", "claude-opus-5", "claude-fable-5", "claude-mythos-5", "claude-mythos-preview", "us.anthropic.claude-sonnet-5-v1:0")) {
            assertTrue(id, AnthropicModels.thinksByDefault(id))
        }
        for (id in listOf("claude-sonnet-4-6", "claude-haiku-4.5", "anthropic.claude-opus-4-8-v1:0", "MiniMax-M3")) {
            assertFalse(id, AnthropicModels.thinksByDefault(id))
        }
        assertTrue(AnthropicModels.defaultSamplingOnly("claude-opus-4-8"))
        assertFalse(AnthropicModels.defaultSamplingOnly("claude-sonnet-4-6"))
    }

    private fun sse(vararg events: String) = events.joinToString("") { "data: $it\n\n" }

    private fun stream(vararg blocks: String, stop: String = "end_turn") = sse(
        """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-5","content":[],"usage":{"input_tokens":10,"output_tokens":1}}}""",
        *blocks,
        """{"type":"message_delta","delta":{"stop_reason":"$stop"},"usage":{"output_tokens":5}}""",
        """{"type":"message_stop"}""",
    )

    private val toolUse = arrayOf(
        """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_1","name":"open_app","input":{}}}""",
        """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"name\":\"Camera\"}"}}""",
    )

    @Test
    fun everyRequestRewritesForeignToolIdsToAnthropicsPatternAndKeepsThemPaired() = runBlocking {
        bench.reply("anthropic/text.sse")
        val history = request(
            user("Open the camera and the clock."),
            ChatMessage.Assistant(
                listOf(
                    AssistantPart.ToolCall("functions.open_app:0", "open_app", obj("""{"name":"Camera"}""")),
                    AssistantPart.ToolCall("call_1", "open_app", obj("""{"name":"Clock"}""")),
                ),
            ),
            ChatMessage.ToolResult("functions.open_app:0", "open_app", "Opened."),
            ChatMessage.ToolResult("call_1", "open_app", "Not run: Kestrel runs one action per step", failed = true),
        )

        adapter.complete(history, anthropic)

        val messages = bench.body(bench.take())["messages"]!!.jsonArray
        val calls = messages[1].jsonObject["content"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        val results = messages[2].jsonObject["content"]!!.jsonArray.map { it.jsonObject["tool_use_id"]!!.jsonPrimitive.content }
        assertEquals(listOf("functions_open_app_0", "call_1"), calls)
        assertEquals(calls, results)
        assertTrue(calls.all { Regex("^[a-zA-Z0-9_-]+$").matches(it) })
    }

    @Test
    fun aThinkingBlockWithoutASignatureGoesBackAsTextOrNotAtAll() = runBlocking {
        bench.status(
            200,
            stream(
                """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
                """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"The owner wants the camera."}}""",
                """{"type":"content_block_start","index":1,"content_block":{"type":"thinking","thinking":" "}}""",
                *toolUse,
                stop = "tool_use",
            ),
            "Content-Type" to "text/event-stream",
        )
        bench.reply("anthropic/text.sse")

        val first = adapter.complete(request(user("Open the camera.")), anthropic)
        adapter.complete(request(user("Open the camera."), first.message, ChatMessage.ToolResult("toolu_1", "open_app", "Opened.")), anthropic)

        bench.take()
        val replayed = bench.body(bench.take())["messages"]!!.jsonArray[1].jsonObject["content"]
        assertEquals(
            json("""[{"type":"text","text":"The owner wants the camera."},{"type":"tool_use","id":"toolu_1","name":"open_app","input":{"name":"Camera"}}]"""),
            replayed,
        )
    }

    @Test
    fun thinkingGoesBackOnlyToTheSameStyleProviderAndModelAndNeverOnCopilotsRoute() = runBlocking {
        bench.reply("anthropic/text.sse")
        bench.reply("anthropic/text.sse")
        val thinking = obj("""{"type":"thinking","thinking":"Open it.","signature":"c2ln"}""")
        val call = AssistantPart.ToolCall("toolu_1", "open_app", obj("""{"name":"Camera"}"""))
        fun history(vararg states: ReasoningState) = request(
            user("Open the camera."),
            ChatMessage.Assistant(states.map { AssistantPart.Reasoning(it) } + call),
            ChatMessage.ToolResult("toolu_1", "open_app", "Opened."),
        )
        val copilot = target(
            "github-copilot", RequestStyle.ANTHROPIC, "https://api.individual.githubcopilot.com",
            ModelEntry("claude-haiku-4.5", "Claude Haiku 4.5", reasoning = true),
            options = StyleOptions(stripThinking = true, sanitizeToolCallIds = true, messagesPath = "/messages"),
        )

        adapter.complete(
            history(
                ReasoningState(RequestStyle.ANTHROPIC, "anthropic", "claude-opus-5", thinking),
                ReasoningState(RequestStyle.ANTHROPIC, "minimax", "claude-sonnet-5", thinking),
                ReasoningState(RequestStyle.RESPONSES, "anthropic", "claude-sonnet-5", thinking),
            ),
            anthropic,
        )
        adapter.complete(history(ReasoningState(RequestStyle.ANTHROPIC, "github-copilot", "claude-haiku-4.5", thinking)), copilot)

        val toolUseOnly = json("""[{"type":"tool_use","id":"toolu_1","name":"open_app","input":{"name":"Camera"}}]""")
        assertEquals(toolUseOnly, bench.body(bench.take())["messages"]!!.jsonArray[1].jsonObject["content"])
        assertEquals(toolUseOnly, bench.body(bench.take())["messages"]!!.jsonArray[1].jsonObject["content"])
    }

    @Test
    fun toolInputThatDoesNotParseIsReportedAsTheModelsText() = runBlocking {
        bench.status(
            200,
            stream(
                """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_1","name":"open_app","input":{}}}""",
                """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"name\":"}}""",
                stop = "tool_use",
            ),
            "Content-Type" to "text/event-stream",
        )

        val response = adapter.complete(request(user("Open the camera.")), anthropic)

        assertEquals("""{"name":""", response.invalidToolCall?.text)
    }

    @Test
    fun textThatACompatibleProviderPutsIntoTheBlockStartIsKept() = runBlocking {
        bench.status(
            200,
            stream(
                """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":"Hello, "}}""",
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"owner."}}""",
            ),
            "Content-Type" to "text/event-stream",
        )

        val response = adapter.complete(request(user("Hi")), anthropic)

        assertEquals(listOf(AssistantPart.Text("Hello, owner.")), response.message.parts)
    }

    @Test
    fun aStreamThatGathersMoreThan8MiBIsMalformed() {
        val piece = "a".repeat(512 * 1024)
        val deltas = List(17) { """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"$piece"}}""" }
        bench.status(
            200,
            stream("""{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""", *deltas.toTypedArray()),
            "Content-Type" to "text/event-stream",
        )

        assertEquals(ProviderError.Malformed, errorOf(adapter, request(user("Hi")), anthropic))
    }
}
