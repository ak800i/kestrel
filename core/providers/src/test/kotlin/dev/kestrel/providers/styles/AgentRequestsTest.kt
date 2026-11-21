package dev.kestrel.providers.styles

import dev.kestrel.providers.catalog.CatalogLoader
import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.CacheFields
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.chat.StyleOptions
import dev.kestrel.providers.copilot.CopilotTargets
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.styles.AdapterHarness.Companion.errorOf
import dev.kestrel.providers.styles.AdapterHarness.Companion.json
import dev.kestrel.providers.styles.AdapterHarness.Companion.obj
import dev.kestrel.providers.styles.AdapterHarness.Companion.request
import dev.kestrel.providers.styles.AdapterHarness.Companion.target
import dev.kestrel.providers.styles.AdapterHarness.Companion.user
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The agent's requests in every style (design D4, D5 and D32 items 8 and 38 of sub-project 5): the per-task context
 * after the system prompt, the cache marks at the end of the part that never changes (`cacheAfter`), Copilot's three
 * cache fields and their switches, the merge of adjacent user messages, the tool-choice flag and the 40-character IDs.
 */
class AgentRequestsTest {
    private val bench = AdapterHarness()

    private val context = "Facts the owner saved: home is Main Street 1."

    // The task, the first screen, one shrunk step (its call and result) and the newest observation; message 3 is the
    // last one that never changes again.
    private val history = arrayOf(
        user("Task: open the camera."),
        user("First screen: Launcher."),
        ChatMessage.Assistant(listOf(AssistantPart.ToolCall("call_1", "open_app", obj("""{"name":"Camera"}""")))),
        ChatMessage.ToolResult("call_1", "open_app", "Step 1: opened Camera."),
        user("Observation 2: Camera"),
    )

    private fun agent(fields: CacheFields = CacheFields.ALL): ChatRequest.() -> ChatRequest =
        { copy(context = this@AgentRequestsTest.context, cacheAfter = 3, cacheFields = fields) }

    private fun body(): JsonObject = bench.body(bench.take())

    /** Every object with the key [key] anywhere in [element], so that a test can count the marks. */
    private fun count(element: kotlinx.serialization.json.JsonElement, key: String): Int = when (element) {
        is JsonObject -> (if (key in element) 1 else 0) + element.values.sumOf { count(it, key) }
        is kotlinx.serialization.json.JsonArray -> element.sumOf { count(it, key) }
        else -> 0
    }

    @After
    fun close() = bench.close()

    @Test
    fun anthropicGetsTheContextAsASecondSystemBlockAndMarksTheStableEnd() = runBlocking {
        bench.reply("anthropic/text.sse")
        val anthropic = target("anthropic", RequestStyle.ANTHROPIC, "https://api.anthropic.com", ModelEntry("claude-sonnet-5", "Claude Sonnet 5"))

        AnthropicAdapter(bench.http).complete(request(*history, block = agent()), anthropic)

        val recorded = bench.take()
        bench.assertBody("anthropic/agent.request.json", recorded)
        // The last tool, the system prompt, the context and message 3: Anthropic's four breakpoints.
        assertEquals(4, count(bench.body(recorded), "cache_control"))
    }

    @Test
    fun anthropicsNewMarksCanBeSwitchedOff() = runBlocking {
        bench.reply("anthropic/text.sse")
        val anthropic = target("anthropic", RequestStyle.ANTHROPIC, "https://api.anthropic.com", ModelEntry("claude-sonnet-5", "Claude Sonnet 5"))

        AnthropicAdapter(bench.http).complete(request(*history, block = agent(CacheFields.NONE)), anthropic)

        val sent = body()
        assertEquals(json("""{"type":"text","text":"$context"}"""), sent["system"]!!.jsonArray[1])
        assertEquals(0, count(sent["messages"]!!, "cache_control"))
        assertEquals(2, count(sent, "cache_control"))
    }

    @Test
    fun copilotsResponsesGetDeveloperItemsAndExplicitBreakpoints() = runBlocking {
        bench.reply("responses/text.sse")
        val terra = target(CatalogLoader.COPILOT, RequestStyle.RESPONSES, "https://api.individual.githubcopilot.com", ModelEntry("gpt-6.1-terra", "GPT-6.1 Terra", reasoning = true), options = StyleOptions(stream = true))

        ResponsesAdapter(bench.http).complete(request(*history, block = agent()), terra)

        bench.assertBody("responses/agent-copilot.request.json", bench.take())
    }

    @Test
    fun explicitModeIsOnlyForTheGatedModelsAndCanBeSwitchedOff() = runBlocking {
        bench.reply("responses/text.sse")
        bench.reply("responses/text.sse")
        val adapter = ResponsesAdapter(bench.http)
        val copilot = target(CatalogLoader.COPILOT, RequestStyle.RESPONSES, "https://api.individual.githubcopilot.com", ModelEntry("gpt-6.1-terra", "GPT-6.1 Terra"))

        adapter.complete(request(*history, block = agent()), copilot.copy(model = ModelEntry("gpt-5.5", "GPT-5.5"), sentModelId = "gpt-5.5"))
        adapter.complete(request(*history, block = agent(CacheFields(responsesExplicit = false))), copilot)

        for (sent in listOf(body(), body())) {
            assertNull(sent["prompt_cache_options"])
            assertEquals(0, count(sent, "prompt_cache_breakpoint"))
            assertEquals(json("""{"role":"developer","content":"$context"}"""), sent["input"]!!.jsonArray[1])
        }
    }

    @Test
    fun otherResponsesTargetsGetASystemItemAndThePlanKeepsItsInstructions() = runBlocking {
        repeat(3) { bench.reply("responses/text.sse") }
        val adapter = ResponsesAdapter(bench.http)
        val xai = target("xai", RequestStyle.RESPONSES, "https://api.x.ai/v1", ModelEntry("grok-4.3", "Grok 4.3"))
        val openAi = target("openai", RequestStyle.RESPONSES, "https://api.openai.com/v1", ModelEntry("gpt-5.5", "GPT-5.5"))
        val plan = target("openai", RequestStyle.RESPONSES, "https://api.openai.com/v1", ModelEntry("gpt-6-luna", "GPT-6 Luna"), options = StyleOptions(chatgptPlan = true))

        adapter.complete(request(*history, block = agent()), xai)
        adapter.complete(request(*history, block = agent()), openAi)
        adapter.complete(request(*history, block = agent()), plan)

        val roles = { sent: JsonObject -> sent["input"]!!.jsonArray.take(2).map { it.jsonObject["role"]!!.jsonPrimitive.content } }
        assertEquals(listOf("system", "system"), roles(body()))
        assertEquals(listOf("system", "developer"), roles(body()))
        val planBody = body()
        assertEquals(json(""""You are Kestrel.""""), planBody["instructions"])
        assertEquals(json("""{"role":"developer","content":"$context"}"""), planBody["input"]!!.jsonArray[0])
    }

    @Test
    fun copilotsChatCompletionsJoinSystemAndContextAndMarkTheMergedMessage() = runBlocking {
        bench.reply("chat-completions/text.sse")
        val gemini = ModelEntry("gemini-3.1-pro", "Gemini 3.1 Pro")
        val copilot = target(CatalogLoader.COPILOT, RequestStyle.CHAT_COMPLETIONS, "https://api.individual.githubcopilot.com", gemini, options = CopilotTargets.options(RequestStyle.CHAT_COMPLETIONS))

        ChatCompletionsAdapter(bench.http).complete(request(*history, block = agent()), copilot)

        bench.assertBody("chat-completions/agent-copilot.request.json", bench.take())
    }

    @Test
    fun copilotsMarkGoesOnTheMergedMessageThatHoldsCacheAfter() = runBlocking {
        bench.reply("chat-completions/text.sse")
        val copilot = target(CatalogLoader.COPILOT, RequestStyle.CHAT_COMPLETIONS, "https://api.individual.githubcopilot.com", ModelEntry("gemini-3.1-pro", "Gemini 3.1 Pro"), options = CopilotTargets.options(RequestStyle.CHAT_COMPLETIONS))

        // Message 1, the first screen, goes in one wire message with message 0, the task.
        ChatCompletionsAdapter(bench.http).complete(request(*history, block = { copy(context = this@AgentRequestsTest.context, cacheAfter = 1) }), copilot)

        val sent = body()
        assertTrue("copilot_cache_control" in sent["messages"]!!.jsonArray[1].jsonObject)
        assertEquals(2, count(sent, "copilot_cache_control"))
    }

    @Test
    fun anthropicStyleMarksOnChatCompletionsKeepTheSystemPromptApartFromTheContext() = runBlocking {
        bench.reply("chat-completions/text.sse")
        val claude = target("openrouter", RequestStyle.CHAT_COMPLETIONS, "https://openrouter.ai/api/v1", ModelEntry("anthropic/claude-sonnet-5", "Claude Sonnet 5"))

        ChatCompletionsAdapter(bench.http).complete(request(*history, block = agent()), claude)

        val mark = """"cache_control":{"type":"ephemeral"}"""
        assertEquals(
            json("""[{"type":"text","text":"You are Kestrel.",$mark},{"type":"text","text":"$context",$mark}]"""),
            body()["messages"]!!.jsonArray[0].jsonObject["content"],
        )
    }

    @Test
    fun chatCompletionsMarksAreCopilotsOnlyAndCanBeSwitchedOff() = runBlocking {
        bench.reply("chat-completions/text.sse")
        bench.reply("chat-completions/text.sse")
        val adapter = ChatCompletionsAdapter(bench.http)
        val copilot = target(CatalogLoader.COPILOT, RequestStyle.CHAT_COMPLETIONS, "https://api.individual.githubcopilot.com", ModelEntry("gemini-3.1-pro", "Gemini 3.1 Pro"), options = CopilotTargets.options(RequestStyle.CHAT_COMPLETIONS))
        val groq = target("groq", RequestStyle.CHAT_COMPLETIONS, "https://api.groq.com/openai/v1", ModelEntry("openai/gpt-oss-120b", "GPT OSS 120B"))

        adapter.complete(request(*history, block = agent(CacheFields(chatCopilotMarks = false))), copilot)
        adapter.complete(request(*history, block = agent()), groq)

        for (sent in listOf(body(), body())) {
            assertEquals(0, count(sent, "copilot_cache_control"))
            val messages = sent["messages"]!!.jsonArray
            assertEquals(listOf("system", "user", "assistant", "tool", "user"), messages.map { it.jsonObject["role"]!!.jsonPrimitive.content })
            assertEquals(json(""""You are Kestrel.\n\n----\n\n$context""""), messages[0].jsonObject["content"])
        }
    }

    @Test
    fun azuresChatCompletionsCutTheLongIdsOfAHistoryFromResponses() = runBlocking {
        bench.reply("chat-completions/text.sse")
        bench.reply("chat-completions/text.sse")
        val adapter = ChatCompletionsAdapter(bench.http)
        val long = "fc_" + "a1b2c3d4".repeat(7) + "e5f6g"
        val escalated = arrayOf(
            user("Task: open the camera."),
            ChatMessage.Assistant(listOf(AssistantPart.ToolCall(long, "open_app", obj("""{"name":"Camera"}""")))),
            ChatMessage.ToolResult(long, "open_app", "Opened."),
        )
        val azure = target("azure-openai", RequestStyle.CHAT_COMPLETIONS, "https://kestrel.openai.azure.com/openai/v1", ModelEntry("gpt-6-luna", "GPT-6 Luna"))
        val deepSeek = target("deepseek", RequestStyle.CHAT_COMPLETIONS, "https://api.deepseek.com", ModelEntry("deepseek-v4-flash", "DeepSeek V4 Flash"))

        adapter.complete(request(*escalated), azure)
        adapter.complete(request(*escalated), deepSeek)

        assertEquals(64, long.length)
        val cut = body()["messages"]!!.jsonArray
        val callId = cut[2].jsonObject["tool_calls"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content
        assertEquals(40, callId.length)
        assertTrue(callId.startsWith("fc_a1b2c3d4"))
        assertEquals(callId, cut[3].jsonObject["tool_call_id"]!!.jsonPrimitive.content)
        val kept = body()["messages"]!!.jsonArray
        assertEquals(long, kept[3].jsonObject["tool_call_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun geminiGetsASecondSystemPartAndOneTurnForAdjacentUserMessages() = runBlocking {
        bench.reply("gemini/text.sse")
        val google = target("google", RequestStyle.GEMINI, "https://generativelanguage.googleapis.com/v1beta", ModelEntry("gemini-3.8-flash", "Gemini 3.8 Flash"))

        GeminiAdapter(bench.http).complete(request(*history, block = agent()), google)

        bench.assertBody("gemini/agent.request.json", bench.take())
    }

    @Test
    fun claudeOnBedrockGetsCachePointsAndOllamaOneSystemMessage() = runBlocking {
        bench.reply("bedrock/text.json")
        bench.reply("bedrock/text.json")
        bench.reply("ollama/text.json")
        val bedrock = BedrockAdapter(bench.http)
        val claude = target("amazon-bedrock", RequestStyle.BEDROCK, "https://bedrock-runtime.us-east-1.amazonaws.com", ModelEntry("anthropic.claude-sonnet-5-v1:0", "Claude Sonnet 5"))
        val ollama = target("ollama", RequestStyle.OLLAMA, "http://127.0.0.1:11434/v1", ModelEntry("qwen3.6", "Qwen3.6"))

        bedrock.complete(request(*history, block = agent()), claude)
        bedrock.complete(request(*history, block = agent(CacheFields.NONE)), claude)
        OllamaAdapter(bench.http).complete(request(*history, block = agent()), ollama)

        val point = json("""{"cachePoint":{"type":"default"}}""")
        val marked = body()
        assertEquals(json("""[{"text":"You are Kestrel."},$point,{"text":"$context"},$point]"""), marked["system"])
        val turns = marked["messages"]!!.jsonArray
        assertEquals(listOf("user", "assistant", "user"), turns.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertEquals(point, turns[2].jsonObject["content"]!!.jsonArray[1])
        val unmarked = body()
        assertEquals(json("""[{"text":"You are Kestrel."},$point,{"text":"$context"}]"""), unmarked["system"])
        assertFalse(unmarked["messages"].toString().contains("cachePoint"))
        val local = bench.body(bench.take())["messages"]!!.jsonArray
        assertEquals(json("""{"role":"system","content":"You are Kestrel.\n\n----\n\n$context"}"""), local[0])
    }

    @Test
    fun aBadRequestThatNamesTheToolChoiceIsFlagged() {
        bench.status(400, """{"error": {"message": "Tool choice 'required' is not supported.", "type": "invalid_request_error", "param": "tool_choice"}}""")
        bench.status(400, """{"error": {"message": "Bad.", "type": "invalid_request_error"}}""")
        val openAi = target("openai", RequestStyle.RESPONSES, "https://api.openai.com/v1", ModelEntry("gpt-5.5", "GPT-5.5"))
        val adapter = ResponsesAdapter(bench.http)

        assertEquals(ProviderError.BadRequest(400, "invalid_request_error", toolChoice = true), errorOf(adapter, request(user("Hi")), openAi))
        assertEquals(ProviderError.BadRequest(400, "invalid_request_error", toolChoice = false), errorOf(adapter, request(user("Hi")), openAi))
        assertEquals("BadRequest(status=400, code=null, toolChoice=true)", ProviderError.BadRequest(400, null, toolChoice = true).toString())
    }

    @Test
    fun theEstimateWithoutUsageCountsTheContext() {
        val plain = request(user("Hi"))
        val withContext = request(user("Hi")) { copy(context = "x".repeat(400)) }

        val difference = Wire.estimate(withContext, 0, 0, false).input - Wire.estimate(plain, 0, 0, false).input

        assertEquals(100L, difference)
    }
}
