package dev.kestrel.providers.styles

import dev.kestrel.providers.catalog.CompatFlags
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
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponsesAdapterTest {
    private val bench = AdapterHarness()
    private val adapter = ResponsesAdapter(bench.http)

    private val openAi = target("openai", RequestStyle.RESPONSES, "https://api.openai.com/v1", ModelEntry("gpt-5.5", "GPT-5.5", reasoning = true))
    private val xai = target("xai", RequestStyle.RESPONSES, "https://api.x.ai/v1", ModelEntry("grok-4.3", "Grok 4.3", reasoning = true))
    private val plan = target(
        "openai", RequestStyle.RESPONSES, "https://api.openai.com/v1",
        ModelEntry(
            "gpt-6-luna", "GPT-6 Luna", reasoning = true,
            compat = CompatFlags(supportsReasoningEffort = true, supportedReasoningEfforts = listOf("none", "low", "medium", "high", "xhigh", "max"), supportsTemperature = false),
            thinkingLevelMap = mapOf("off" to "none", "minimal" to "low", "xhigh" to "xhigh", "max" to "max"),
        ),
        options = StyleOptions(chatgptPlan = true),
    )

    @After
    fun close() = bench.close()

    private fun sse(vararg events: String) = events.joinToString("") { "data: $it\n\n" }

    @Test
    fun aTextAnswerIsGatheredWithItsUsageAndOpenAiGetsThePromptCacheKey() = runBlocking {
        bench.reply("responses/text.sse")

        val response = adapter.complete(request(user("Hi")) { copy(reasoning = ReasoningLevel.LOW, cacheKey = "kestrel-agent-v1") }, openAi)

        assertEquals(listOf(AssistantPart.Text("Hello, owner.")), response.message.parts)
        assertEquals(StopReason.END, response.stop)
        assertEquals(Usage(input = 300, output = 50, cacheRead = 1500, cacheWrite = 200, reasoning = 30), response.usage)
        assertEquals("gpt-5.5-2026-08-01", response.modelId)
        assertEquals(listOf("https://api.openai.com/v1/responses".toHttpUrl()), bench.calls.requests)
        bench.assertBody("responses/text.request.json", bench.take())
    }

    @Test
    fun theEncryptedReasoningGoesBackWithoutItsIdBeforeTheCallAndItsOutput() = runBlocking {
        bench.reply("responses/tool-call.sse")
        bench.reply("responses/text.sse")

        val first = adapter.complete(request(user("Open the camera.")), openAi)

        val item = obj("""{"type":"reasoning","summary":[],"encrypted_content":"gAAAAABpZXhhbXBsZS1lbmNyeXB0ZWQtcmVhc29uaW5n"}""")
        assertEquals(
            listOf(
                AssistantPart.Reasoning(ReasoningState(RequestStyle.RESPONSES, "openai", "gpt-5.5", item)),
                AssistantPart.ToolCall("call_1", "open_app", obj("""{"name":"Camera"}""")),
            ),
            first.message.parts,
        )
        assertEquals(StopReason.TOOL_CALL, first.stop)
        adapter.complete(request(user("Open the camera."), first.message, ChatMessage.ToolResult("call_1", "open_app", "Opened.")), openAi)

        bench.take()
        val input = bench.body(bench.take())["input"]!!.jsonArray
        assertEquals(item, input[2])
        assertEquals(json("""{"type":"function_call","call_id":"call_1","name":"open_app","arguments":"{\"name\":\"Camera\"}"}"""), input[3])
        assertEquals(json("""{"type":"function_call_output","call_id":"call_1","output":"Opened."}"""), input[4])
    }

    @Test
    fun anotherModelGetsNoReasoningItemsAndXaiGetsNoCacheKey() = runBlocking {
        bench.reply("responses/two-calls.sse")
        bench.reply("responses/text.sse")

        val first = adapter.complete(request(user("Open the camera and the clock.")) { copy(cacheKey = "kestrel-agent-v1") }, xai)

        assertEquals(listOf("call_a", "call_b"), first.message.toolCalls.map { it.id })
        val history = listOf(
            user("Open the camera and the clock."),
            ChatMessage.Assistant(listOf(AssistantPart.Reasoning(ReasoningState(RequestStyle.RESPONSES, "xai", "grok-4.5", obj("""{"type":"reasoning"}""")))) + first.message.parts),
            ChatMessage.ToolResult("call_a", "open_app", "Opened."),
            ChatMessage.ToolResult("call_b", "open_app", "Not run: Kestrel runs one action per step", failed = true),
        )
        adapter.complete(request(*history.toTypedArray()), xai)

        assertNull(bench.body(bench.take())["prompt_cache_key"])
        val input = bench.body(bench.take())["input"]!!.jsonArray
        assertEquals(listOf("system", "user"), input.take(2).map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertEquals(
            listOf("function_call", "function_call", "function_call_output", "function_call_output"),
            input.drop(2).map { it.jsonObject["type"]!!.jsonPrimitive.content },
        )
    }

    @Test
    fun anImageGoesAsAnInputImage() = runBlocking {
        bench.reply("responses/text.sse")

        adapter.complete(request(ChatMessage.User(listOf(Part.Text("Read this."), Part.Image(ImageType.JPEG, SCREENSHOT)))), openAi)

        val content = bench.body(bench.take())["input"]!!.jsonArray[1].jsonObject["content"]
        assertEquals(json("""[{"type":"input_text","text":"Read this."},{"type":"input_image","image_url":"data:image/jpeg;base64,iVBORw0KGgo="}]"""), content)
    }

    @Test
    fun aReplyWithoutUsageIsEstimatedFromItsItems() = runBlocking {
        bench.reply("responses/no-usage.sse")

        val response = adapter.complete(request(user("Hi")), openAi)

        assertEquals(listOf(AssistantPart.Text("Hello, owner.")), response.message.parts)
        assertTrue(response.usage.estimated)
    }

    @Test
    fun aClosingDoneLineAndAnEventNamedOnlyInItsSseLineAreReadAndAModelThatDoesNotReasonGetsNoInclude() = runBlocking {
        bench.reply("responses/done-line.sse")
        val gpt41 = target("openai", RequestStyle.RESPONSES, "https://api.openai.com/v1", ModelEntry("gpt-4.1", "GPT-4.1"))

        val response = adapter.complete(request(user("Hi")), gpt41)

        assertEquals(listOf(AssistantPart.Text("Hello, owner.")), response.message.parts)
        assertEquals(StopReason.END, response.stop)
        assertNull(bench.body(bench.take())["include"])
    }

    @Test
    fun anIncompleteResponseKeepsItsOutputAndStopsByItsReason() = runBlocking {
        bench.reply("responses/incomplete-max-tokens.sse")
        bench.reply("responses/incomplete-filter.sse")

        val long = adapter.complete(request(user("List everything.")), openAi)
        val filtered = adapter.complete(request(user("Something filtered.")), openAi)

        assertEquals(StopReason.MAX_TOKENS, long.stop)
        assertEquals(listOf(AssistantPart.Text("The list is long, so I")), long.message.parts)
        assertEquals(256L, long.usage.output)
        assertEquals(StopReason.FILTERED, filtered.stop)
    }

    @Test
    fun aRefusedEncryptedReasoningIsSentOnceMoreWithoutReasoningItems() = runBlocking {
        bench.status(400, """{"error":{"code":"invalid_encrypted_content","message":"could not decrypt"}}""")
        bench.reply("responses/text.sse")
        val state = ReasoningState(RequestStyle.RESPONSES, "openai", "gpt-5.5", obj("""{"type":"reasoning","encrypted_content":"x"}"""))
        val call = AssistantPart.ToolCall("call_1", "open_app", obj("""{"name":"Camera"}"""))
        val history = request(user("Open the camera."), ChatMessage.Assistant(listOf(AssistantPart.Reasoning(state), call)), ChatMessage.ToolResult("call_1", "open_app", "Opened."))

        val response = adapter.complete(history, openAi)

        assertEquals(StopReason.END, response.stop)
        assertEquals(5, bench.body(bench.take())["input"]!!.jsonArray.size)
        assertEquals(4, bench.body(bench.take())["input"]!!.jsonArray.size)
    }

    @Test
    fun theStatusErrorsAndTheErrorsInsideTheStreamAreMapped() {
        bench.status(401)
        bench.status(401)
        bench.status(429, """{"error":{"code":"rate_limit_exceeded"}}""", "Retry-After" to "3")
        bench.status(500)
        bench.reply("responses/failed.sse")
        bench.reply("responses/error-event.sse")
        val auth = AdapterHarness.RefreshingAuth()

        assertEquals(ProviderError.Unauthorized, errorOf(adapter, request(user("Hi")), openAi.copy(auth = auth)))
        assertEquals(1, auth.refreshes)
        assertEquals(ProviderError.RateLimited(3_000, "rate_limit_exceeded"), errorOf(adapter, request(user("Hi")), openAi))
        assertEquals(ProviderError.ServerError(500), errorOf(adapter, request(user("Hi")), openAi))
        assertEquals(ProviderError.ServerError(500), errorOf(adapter, request(user("Hi")), openAi))
        assertEquals(ProviderError.RateLimited(null, "rate_limit_exceeded"), errorOf(adapter, request(user("Hi")), openAi))
    }

    @Test
    fun aMalformedEventAndAStreamWithoutItsFinalEventAreMalformed() {
        bench.reply("responses/malformed.sse")
        bench.reply("responses/ends-early.sse")

        assertEquals(ProviderError.Malformed, errorOf(adapter, request(user("Hi")), openAi))
        assertEquals(ProviderError.Malformed, errorOf(adapter, request(user("Hi")), openAi))
    }

    @Test
    fun theChatGptPlanSendsItsInstructionsAndTheToolsInTheKestrelNamespaceAndNoRejectedField() = runBlocking {
        bench.reply("responses/plan-tool-call.sse")

        val response = adapter.complete(
            request(user("Open the camera.")) { copy(reasoning = ReasoningLevel.LOW, temperature = 0.2, cacheKey = "kestrel-agent-v1") },
            plan,
        )

        assertEquals(listOf(AssistantPart.ToolCall("call_p", "open_app", obj("""{"name":"Camera"}"""))), response.message.parts)
        bench.assertBody("responses/plan.request.json", bench.take())
    }

    @Test
    fun theChatGptPlanReplaysANamespacedCallWithItsNamespace() = runBlocking {
        bench.reply("responses/text.sse")
        val call = AssistantPart.ToolCall("call_p", "open_app", obj("""{"name":"Camera"}"""))

        adapter.complete(
            request(user("Open the camera."), ChatMessage.Assistant(listOf(call)), ChatMessage.ToolResult("call_p", "open_app", "Opened.")) { copy(system = "") },
            plan,
        )

        val body = bench.body(bench.take())
        assertNull(body["instructions"])
        val input = body["input"]!!.jsonArray
        assertEquals(json("""{"type":"function_call","call_id":"call_p","name":"open_app","namespace":"kestrel","arguments":"{\"name\":\"Camera\"}"}"""), input[1])
    }

    @Test
    fun eachChatGptPlanCodeInsideTheStreamMapsAsDesignD17Says() {
        val expected = mapOf(
            "subscription_sharing_usage_limit_exceeded" to ProviderError.QuotaOrPolicy(429, "subscription_sharing_usage_limit_exceeded"),
            "subscription_sharing_user_not_eligible" to ProviderError.QuotaOrPolicy(403, "subscription_sharing_user_not_eligible"),
            "subscription_sharing_route_not_supported" to ProviderError.QuotaOrPolicy(403, "subscription_sharing_route_not_supported"),
            "chatpass_v2_scope_not_authorized" to ProviderError.QuotaOrPolicy(403, "chatpass_v2_scope_not_authorized"),
            "chatpass_v2_invalid_authorization_context" to ProviderError.QuotaOrPolicy(403, "chatpass_v2_invalid_authorization_context"),
            "subscription_sharing_invalid_user" to ProviderError.SignInExpired("openai"),
            "subscription_sharing_usage_unavailable" to ProviderError.ServerError(503),
            "subscription_sharing_user_unavailable" to ProviderError.ServerError(503),
            "subscription_sharing_unsupported_capability" to ProviderError.BadRequest(400, "subscription_sharing_unsupported_capability"),
        )
        for ((code, error) in expected) {
            bench.server.enqueue(
                MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream")
                    .body(sse("""{"type":"response.failed","response":{"status":"failed","error":{"code":"$code","message":"Usage limit."}}}""")).build(),
            )
            assertEquals(code, error, errorOf(adapter, request(user("Hi")), plan))
        }
    }

    @Test
    fun everyChatGptPlan429BeforeTheStreamIsAPlanLimitWithOrWithoutACode() {
        bench.status(429, """{"error":{"code":"subscription_sharing_usage_limit_exceeded","message":"Limit."}}""")
        bench.status(429, """{"detail":"Usage limit reached."}""")
        bench.status(403, """{"error":{"code":"subscription_sharing_user_not_eligible"}}""")

        assertEquals(ProviderError.QuotaOrPolicy(429, "subscription_sharing_usage_limit_exceeded"), errorOf(adapter, request(user("Hi")), plan))
        assertEquals(ProviderError.QuotaOrPolicy(429, null), errorOf(adapter, request(user("Hi")), plan))
        assertEquals(ProviderError.QuotaOrPolicy(403, "subscription_sharing_user_not_eligible"), errorOf(adapter, request(user("Hi")), plan))
    }

    @Test
    fun theEstimateCountsTheReasoningOnceAndAlsoWithoutEncryptedContent() = runBlocking {
        val summary = "a".repeat(400)
        val message = """{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Hello, owner."}]}"""
        val completed = """{"type":"response.completed","response":{"status":"completed","output":[]}}"""
        bench.status(
            200,
            sse(
                """{"type":"response.reasoning_summary_text.delta","delta":"$summary"}""",
                """{"type":"response.output_item.done","output_index":0,"item":{"type":"reasoning","summary":[{"type":"summary_text","text":"$summary"}],"encrypted_content":"x"}}""",
                """{"type":"response.output_item.done","output_index":1,"item":$message}""",
                completed,
            ),
            "Content-Type" to "text/event-stream",
        )
        bench.status(
            200,
            sse(
                """{"type":"response.output_item.done","output_index":0,"item":{"type":"reasoning","summary":[{"type":"summary_text","text":"$summary"}]}}""",
                """{"type":"response.output_item.done","output_index":1,"item":$message}""",
                completed,
            ),
            "Content-Type" to "text/event-stream",
        )

        val withDeltas = adapter.complete(request(user("Hi")), openAi)
        val withoutEncrypted = adapter.complete(request(user("Hi")), openAi)

        assertEquals(100L, withDeltas.usage.reasoning)
        assertEquals(100L, withoutEncrypted.usage.reasoning)
        assertTrue(withoutEncrypted.usage.estimated)
    }

    @Test
    fun withoutStreamingTheReplyIsOneResponseObject() = runBlocking {
        bench.status(
            200,
            """{"id":"resp_1","object":"response","model":"gpt-5.5-2026-08-01","status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Hello, owner."}]}],"usage":{"input_tokens":100,"output_tokens":5}}""",
            "Content-Type" to "application/json",
        )

        val response = adapter.complete(request(user("Hi")), openAi.copy(options = StyleOptions(stream = false)))

        assertEquals(listOf(AssistantPart.Text("Hello, owner.")), response.message.parts)
        assertEquals(Usage(input = 100, output = 5), response.usage)
        val recorded = bench.take()
        assertEquals("application/json", recorded.headers["accept"])
        assertEquals(json("false"), bench.body(recorded)["stream"])
    }

    @Test
    fun aFailedResponseWithoutStreamingFailsWithItsErrorOrAsMalformed() {
        bench.status(200, """{"id":"resp_1","object":"response","status":"failed","output":[],"error":{"code":"server_error","message":"The server had an error."}}""", "Content-Type" to "application/json")
        bench.status(200, """{"id":"resp_1","object":"response","status":"failed","output":[]}""", "Content-Type" to "application/json")
        val unstreamed = openAi.copy(options = StyleOptions(stream = false))

        assertEquals(ProviderError.ServerError(500), errorOf(adapter, request(user("Hi")), unstreamed))
        assertEquals(ProviderError.Malformed, errorOf(adapter, request(user("Hi")), unstreamed))
    }

    @Test
    fun theChatGptPlanSendsNoTemperatureAndAsksForTheEncryptedReasoningOfAnyModel() = runBlocking {
        bench.reply("responses/plan-tool-call.sse")
        val plain = plan.copy(model = ModelEntry("gpt-6-luna", "GPT-6 Luna"))

        adapter.complete(request(user("Open the camera.")) { copy(temperature = 0.2) }, plain)

        val body = bench.body(bench.take())
        assertNull(body["temperature"])
        assertEquals(json("""["reasoning.encrypted_content"]"""), body["include"])
    }

    @Test
    fun onlyA400ForAHistoryThatSentReasoningItemsIsSentOnceMore() {
        bench.status(422, """{"error":{"code":"invalid_encrypted_content","message":"could not decrypt"}}""")
        bench.status(400, """{"error":{"code":"invalid_encrypted_content","message":"could not decrypt"}}""")
        val call = AssistantPart.ToolCall("call_1", "open_app", obj("""{"name":"Camera"}"""))
        fun history(state: ReasoningState) =
            request(user("Open the camera."), ChatMessage.Assistant(listOf(AssistantPart.Reasoning(state), call)), ChatMessage.ToolResult("call_1", "open_app", "Opened."))
        val own = ReasoningState(RequestStyle.RESPONSES, "openai", "gpt-5.5", obj("""{"type":"reasoning","encrypted_content":"x"}"""))
        val another = ReasoningState(RequestStyle.RESPONSES, "openai", "gpt-5.4", obj("""{"type":"reasoning","encrypted_content":"x"}"""))

        assertEquals(ProviderError.BadRequest(422, "invalid_encrypted_content"), errorOf(adapter, history(own), openAi))
        assertEquals(ProviderError.BadRequest(400, "invalid_encrypted_content"), errorOf(adapter, history(another), openAi))
        assertEquals(2, bench.server.requestCount)
    }

    @Test
    fun withoutAFinalOutputTheItemsGoByTheirIndexAndElseTheTextDeltasAreTheReply() = runBlocking {
        val completed = """{"type":"response.completed","response":{"status":"completed","output":[],"usage":{"input_tokens":10,"output_tokens":5}}}"""
        bench.status(
            200,
            sse(
                """{"type":"response.output_item.done","output_index":1,"item":{"type":"function_call","call_id":"call_1","name":"open_app","arguments":"{\"name\":\"Camera\"}"}}""",
                """{"type":"response.output_item.done","output_index":0,"item":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Opening."}]}}""",
                completed,
            ),
            "Content-Type" to "text/event-stream",
        )
        bench.status(
            200,
            sse("""{"type":"response.output_text.delta","delta":"Hello, "}""", """{"type":"response.output_text.delta","delta":"owner."}""", completed),
            "Content-Type" to "text/event-stream",
        )

        val ordered = adapter.complete(request(user("Open the camera.")), openAi)
        val deltas = adapter.complete(request(user("Hi")), openAi)

        assertEquals(listOf(AssistantPart.Text("Opening."), AssistantPart.ToolCall("call_1", "open_app", obj("""{"name":"Camera"}"""))), ordered.message.parts)
        assertEquals(listOf(AssistantPart.Text("Hello, owner.")), deltas.message.parts)
    }

    @Test
    fun aStreamThatGathersMoreThan8MiBIsMalformed() {
        val piece = "a".repeat(512 * 1024)
        val item = """{"type":"response.output_item.done","output_index":0,"item":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"$piece"}]}}"""
        val events = List(17) { item } + """{"type":"response.completed","response":{"status":"completed","output":[]}}"""
        bench.status(200, sse(*events.toTypedArray()), "Content-Type" to "text/event-stream")

        assertEquals(ProviderError.Malformed, errorOf(adapter, request(user("Hi")), openAi))
    }

    @Test
    fun aModelThatTakesNoReasoningEffortGetsNoReasoningSettings() = runBlocking {
        bench.reply("responses/text.sse")
        val grok = xai.copy(model = ModelEntry("grok-4.3", "Grok 4.3", reasoning = true, compat = CompatFlags(supportsReasoningEffort = false)))

        adapter.complete(request(user("Hi")) { copy(reasoning = ReasoningLevel.HIGH) }, grok)

        assertNull(bench.body(bench.take())["reasoning"])
    }

    @Test
    fun aReasoningItemWhoseOnlyCallWasLeftOutDoesNotGoBackAlone() = runBlocking {
        bench.reply("responses/tool-call.sse")
        bench.reply("responses/text.sse")
        val tools = listOf(AdapterHarness.GET_NUMBER)

        val first = adapter.complete(request(user("Open the camera."), tools = tools), openAi)
        adapter.complete(request(user("Open the camera."), first.message, user("Use a tool that you have."), tools = tools), openAi)

        assertTrue(first.message.parts.single() is AssistantPart.Reasoning)
        bench.take()
        val types = bench.body(bench.take())["input"]!!.jsonArray.mapNotNull { it.jsonObject["type"]?.jsonPrimitive?.content }
        assertTrue("reasoning" !in types)
    }

    @Test
    fun aCallIdLongerThan64CharactersIsRewrittenAlikeForTheCallAndItsResult() = runBlocking {
        bench.reply("responses/text.sse")
        val long = "call_" + "a".repeat(150) + "__thought__" + "b+/=".repeat(8) + "c".repeat(2)
        val call = AssistantPart.ToolCall(long, "open_app", obj("""{"name":"Camera"}"""))

        adapter.complete(request(user("Open the camera."), ChatMessage.Assistant(listOf(call)), ChatMessage.ToolResult(long, "open_app", "Opened.")), openAi)

        val ids = bench.body(bench.take())["input"]!!.jsonArray.mapNotNull { it.jsonObject["call_id"]?.jsonPrimitive?.content }
        assertEquals(200, long.length)
        assertEquals(2, ids.size)
        assertEquals(ids[0], ids[1])
        assertTrue(Regex("^[A-Za-z0-9_-]{1,64}$").matches(ids[0]))
    }

    @Test
    fun onlyAReasoningItemWithEncryptedContentAndACompletedOrNoStatusIsKept() = runBlocking {
        val output = """[{"id":"rs_1","type":"reasoning","summary":[],"encrypted_content":""},""" +
            """{"id":"rs_2","type":"reasoning","summary":[],"encrypted_content":"x","status":"in_progress"},""" +
            """{"id":"rs_3","type":"reasoning","summary":[],"encrypted_content":"y","status":null},""" +
            """{"id":"msg_1","type":"message","role":"assistant","content":[{"type":"output_text","text":"Hi."}]}]"""
        val completed = """{"type":"response.completed","response":{"status":"completed","output":$output,"usage":{"input_tokens":10,"output_tokens":5}}}"""
        bench.status(200, sse(completed), "Content-Type" to "text/event-stream")

        val response = adapter.complete(request(user("Hi")), openAi)

        val kept = response.message.parts.filterIsInstance<AssistantPart.Reasoning>().map { it.state.payload }
        assertEquals(listOf(json("""{"type":"reasoning","summary":[],"encrypted_content":"y"}""")), kept)
    }
}
