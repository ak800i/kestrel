package dev.kestrel.providers.styles

import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ImageType
import dev.kestrel.providers.chat.InvalidKind
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.chat.ReasoningState
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.chat.StopReason
import dev.kestrel.providers.chat.ToolChoice
import dev.kestrel.providers.chat.Usage
import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderHttp
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The fixtures follow AWS's Converse API reference (`Converse`, `ContentBlock`, `ReasoningContentBlock`, `ToolChoice` and
 * `TokenUsage`), as research r34 read them, because no request went to Bedrock.
 */
class BedrockAdapterTest {
    private val bench = AdapterHarness()
    private val logPaths = mutableListOf<String?>()
    private val adapter = BedrockAdapter(ProviderHttp(bench.calls, decorator = { _, meta -> logPaths += meta.logPath }))
    private val bedrock = target(
        "amazon-bedrock", RequestStyle.BEDROCK, "https://bedrock-runtime.us-east-1.amazonaws.com",
        ModelEntry("anthropic.claude-sonnet-5-v1:0", "Claude Sonnet 5"),
    )

    @After
    fun close() = bench.close()

    @Test
    fun aTextAnswerIsReadWithItsUsageAndTheLogSeesNoModelId() = runBlocking {
        bench.reply("bedrock/text.json")

        val response = adapter.complete(request(user("Hi")), bedrock)

        assertEquals(listOf(AssistantPart.Text("Hello, owner.")), response.message.parts)
        assertEquals(StopReason.END, response.stop)
        assertEquals(Usage(input = 100, output = 12, cacheRead = 900, cacheWrite = 50), response.usage)
        assertEquals("https://bedrock-runtime.us-east-1.amazonaws.com/model/anthropic.claude-sonnet-5-v1%3A0/converse", bench.calls.requests.single().toString())
        assertEquals(listOf<String?>("/model/{model}/converse"), logPaths)
        assertEquals("application/json", bench.take().headers["accept"])
    }

    @Test
    fun theToolResultAndTheNextScreenBecomeOneUserTurn() = runBlocking {
        bench.reply("bedrock/tool-call.json")
        bench.reply("bedrock/text.json")

        val first = adapter.complete(request(user("Open the camera.")), bedrock)

        assertEquals(
            listOf(AssistantPart.Text("I will open it."), AssistantPart.ToolCall("tooluse_1", "open_app", obj("""{"name":"Camera"}"""))),
            first.message.parts,
        )
        assertEquals(StopReason.TOOL_CALL, first.stop)
        val next = request(
            user("Open the camera."),
            first.message,
            ChatMessage.ToolResult("tooluse_1", "open_app", "Opened."),
            ChatMessage.User(listOf(Part.Text("The screen now."), Part.Image(ImageType.PNG, SCREENSHOT))),
        )
        adapter.complete(next, bedrock)

        bench.take()
        bench.assertBody("bedrock/tool-result.request.json", bench.take())
    }

    @Test
    fun twoCallsAreKeptAndTheirResultsShareOneTurnWithTheFailedStatusForClaude() = runBlocking {
        bench.reply("bedrock/two-calls.json")
        bench.reply("bedrock/text.json")

        val first = adapter.complete(request(user("Open the camera and the clock.")), bedrock)

        assertEquals(listOf("tooluse_a", "tooluse_b"), first.message.toolCalls.map { it.id })
        adapter.complete(
            request(
                user("Open the camera and the clock."),
                first.message,
                ChatMessage.ToolResult("tooluse_a", "open_app", " "),
                ChatMessage.ToolResult("tooluse_b", "open_app", "Not run: Kestrel runs one action per step", failed = true),
            ) { copy(system = " ") },
            bedrock,
        )

        bench.take()
        val body = bench.body(bench.take())
        // Converse refuses blank text: no system block, and a blank tool result says "(no output)".
        assertNull(body["system"])
        val messages = body["messages"]!!.jsonArray
        assertEquals(3, messages.size)
        assertEquals(
            json("""[{"toolResult":{"toolUseId":"tooluse_a","content":[{"text":"(no output)"}]}},{"toolResult":{"toolUseId":"tooluse_b","content":[{"text":"Not run: Kestrel runs one action per step"}],"status":"error"}}]"""),
            messages[2].jsonObject["content"],
        )
    }

    @Test
    fun claudesSignedReasoningGoesBackAndAModelThatThinksByDefaultGetsNoForcedChoiceOrTemperature() = runBlocking {
        bench.reply("bedrock/thinking-tool-call.json")
        bench.reply("bedrock/text.json")
        bench.reply("bedrock/text.json")
        val sonnet46 = bedrock.copy(model = ModelEntry("anthropic.claude-sonnet-4-6", "Claude Sonnet 4.6"), sentModelId = "anthropic.claude-sonnet-4-6")

        val first = adapter.complete(request(user("Open the camera.")) { copy(toolChoice = ToolChoice.REQUIRED, temperature = 0.2) }, bedrock)
        adapter.complete(request(user("Open the camera."), first.message, ChatMessage.ToolResult("tooluse_t", "open_app", "Opened.")), bedrock)
        adapter.complete(request(user("Open the camera.")) { copy(toolChoice = ToolChoice.REQUIRED, temperature = 0.2) }, sonnet46)

        val signed = obj("""{"reasoningContent":{"reasoningText":{"text":"The owner wants the camera.","signature":"RXF3U2lnbmF0dXJl"}}}""")
        val redacted = obj("""{"reasoningContent":{"redactedContent":"UmVkYWN0ZWQ="}}""")
        assertEquals(
            listOf(
                AssistantPart.Reasoning(ReasoningState(RequestStyle.BEDROCK, "amazon-bedrock", "anthropic.claude-sonnet-5-v1:0", signed)),
                AssistantPart.Reasoning(ReasoningState(RequestStyle.BEDROCK, "amazon-bedrock", "anthropic.claude-sonnet-5-v1:0", redacted)),
                AssistantPart.ToolCall("tooluse_t", "open_app", obj("""{"name":"Camera"}""")),
            ),
            first.message.parts,
        )
        val forced = bench.body(bench.take())
        assertEquals(json("""{"auto":{}}"""), forced["toolConfig"]!!.jsonObject["toolChoice"])
        assertNull(forced["inferenceConfig"]!!.jsonObject["temperature"])
        val replayed = bench.body(bench.take())["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
        assertEquals(listOf(signed, redacted), replayed.take(2))
        val plain = bench.body(bench.take())
        assertEquals(json("""{"any":{}}"""), plain["toolConfig"]!!.jsonObject["toolChoice"])
        assertEquals(json("0.2"), plain["inferenceConfig"]!!.jsonObject["temperature"])

        // Without tools the history is text, so no reasoning goes back, and the reply to such a request keeps none.
        bench.reply("bedrock/thinking-tool-call.json")
        val withoutTools = adapter.complete(
            request(user("Open the camera."), first.message, ChatMessage.ToolResult("tooluse_t", "open_app", "Opened.")) { copy(toolChoice = ToolChoice.NONE) },
            bedrock,
        )
        val textOnly = bench.body(bench.take())
        assertNull(textOnly["toolConfig"])
        assertEquals(
            json("""[{"text":"[tool call id=tooluse_t name=open_app] {\"name\":\"Camera\"}"}]"""),
            textOnly["messages"]!!.jsonArray[1].jsonObject["content"],
        )
        assertTrue(withoutTools.message.parts.none { it is AssistantPart.Reasoning })
        // OpenClaw keeps a forced tool choice for Claude Haiku 5.5.
        bench.reply("bedrock/text.json")
        val haiku55 = bedrock.copy(model = ModelEntry("anthropic.claude-haiku-5-5-v1:0", "Claude Haiku 5.5"), sentModelId = "anthropic.claude-haiku-5-5-v1:0")
        adapter.complete(request(user("Hi")) { copy(toolChoice = ToolChoice.REQUIRED, temperature = 0.2) }, haiku55)
        val haiku = bench.body(bench.take())
        assertEquals(json("""{"any":{}}"""), haiku["toolConfig"]!!.jsonObject["toolChoice"])
        assertNull(haiku["inferenceConfig"]!!.jsonObject["temperature"])
    }

    @Test
    fun withoutToolsTheHistoryIsTextAndThereIsNoToolConfig() = runBlocking {
        bench.reply("bedrock/no-usage.json")
        val call = AssistantPart.ToolCall("tooluse_1", "open_app", obj("""{"name":"Camera"}"""))

        val response = adapter.complete(
            request(user("Open the camera."), ChatMessage.Assistant(listOf(call)), ChatMessage.ToolResult("tooluse_1", "open_app", "Opened."), tools = emptyList()),
            bedrock,
        )

        assertTrue(response.usage.estimated)
        val body = bench.body(bench.take())
        assertNull(body["toolConfig"])
        assertEquals(
            json("""[{"role":"user","content":[{"text":"Open the camera."}]},{"role":"assistant","content":[{"text":"[tool call id=tooluse_1 name=open_app] {\"name\":\"Camera\"}"}]},{"role":"user","content":[{"text":"[tool result id=tooluse_1 name=open_app] Opened."}]}]"""),
            body["messages"],
        )
    }

    @Test
    fun iamKeysSignTheRequestAndAwsTokenErrorsAreTheCredentialsOwnRefusal() = runBlocking {
        val unrecognized = "UnrecognizedClientException:http://internal.amazon.com/coral/com.amazon.coral.service/"
        bench.status(403, """{"message":"The security token included in the request is invalid."}""", "x-amzn-ErrorType" to unrecognized)
        bench.status(403, """{"message":"The security token included in the request is invalid."}""", "x-amzn-ErrorType" to unrecognized)
        val keys = Credential.AwsAccessKeys("AKIDEXAMPLE", "secret", "session-token")
        val signed = bedrock.copy(auth = SigV4Auth(keys, "us-east-1", clock = { 0L }))

        assertEquals(ProviderError.SignInExpired("amazon-bedrock"), errorOf(adapter, request(user("Hi")), signed))
        // An API key that AWS refuses is a refused key, as its 401 is.
        assertEquals(ProviderError.Unauthorized, errorOf(adapter, request(user("Hi")), bedrock))
        val recorded = bench.take()
        assertTrue(recorded.headers["Authorization"]!!.startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/19700101/us-east-1/bedrock/aws4_request, SignedHeaders=host;x-amz-date;x-amz-security-token, Signature="))
        assertEquals("session-token", recorded.headers["x-amz-security-token"])
        assertEquals("19700101T000000Z", recorded.headers["x-amz-date"])
    }

    @Test
    fun theOtherStatusErrorsAndAMalformedBodyAreMapped() {
        bench.status(401)
        bench.status(401)
        bench.status(403, """{"message":"denied"}""", "x-amzn-ErrorType" to "AccessDeniedException")
        bench.status(429, """{"message":"slow"}""", "x-amzn-ErrorType" to "ThrottlingException", "Retry-After" to "2")
        bench.status(500)
        bench.reply("bedrock/malformed.json")
        val auth = AdapterHarness.RefreshingAuth()

        assertEquals(ProviderError.Unauthorized, errorOf(adapter, request(user("Hi")), bedrock.copy(auth = auth)))
        assertEquals(1, auth.refreshes)
        assertEquals(ProviderError.QuotaOrPolicy(403, "AccessDeniedException"), errorOf(adapter, request(user("Hi")), bedrock))
        assertEquals(ProviderError.RateLimited(2_000, "ThrottlingException"), errorOf(adapter, request(user("Hi")), bedrock))
        assertEquals(ProviderError.ServerError(500), errorOf(adapter, request(user("Hi")), bedrock))
        assertEquals(ProviderError.Malformed, errorOf(adapter, request(user("Hi")), bedrock))
    }

    @Test
    fun aRefusedSignatureWithAClockSkewIsSignedOnceMoreWithAwsTime() = runBlocking {
        val clocked = BedrockAdapter(ProviderHttp(bench.calls, clock = { NOW }))
        val signed = bedrock.copy(auth = SigV4Auth(Credential.AwsAccessKeys("AKIDEXAMPLE", "secret"), "us-east-1", clock = { NOW }))
        bench.status(403, SIGNATURE_EXPIRED, "x-amzn-ErrorType" to "InvalidSignatureException", "Date" to httpDate(NOW + 3_600_000))
        bench.reply("bedrock/text.json")

        val response = clocked.complete(request(user("Hi")), signed)

        assertEquals(listOf(AssistantPart.Text("Hello, owner.")), response.message.parts)
        assertEquals("20261119T100000Z", bench.take().headers["x-amz-date"])
        assertEquals("20261119T110000Z", bench.take().headers["x-amz-date"])
    }

    @Test
    fun aSecondRefusalOrARefusalWithoutASkewIsTheCredentialsRefusal() {
        val clocked = BedrockAdapter(ProviderHttp(bench.calls, clock = { NOW }))
        val signed = bedrock.copy(auth = SigV4Auth(Credential.AwsAccessKeys("AKIDEXAMPLE", "secret"), "us-east-1", clock = { NOW }))
        bench.status(403, SIGNATURE_EXPIRED, "x-amzn-ErrorType" to "InvalidSignatureException", "Date" to httpDate(NOW + 3_600_000))
        bench.status(403, SIGNATURE_EXPIRED, "x-amzn-ErrorType" to "InvalidSignatureException", "Date" to httpDate(NOW + 3_600_000))
        // Clocks that agree within 5 minutes, and a reply without a Date.
        bench.status(403, SIGNATURE_EXPIRED, "x-amzn-ErrorType" to "InvalidSignatureException", "Date" to httpDate(NOW + 120_000))
        bench.status(403, SIGNATURE_EXPIRED, "x-amzn-ErrorType" to "InvalidSignatureException")
        bench.reply("bedrock/text.json")

        assertEquals(ProviderError.SignInExpired("amazon-bedrock"), errorOf(clocked, request(user("Hi")), signed))
        assertEquals(ProviderError.SignInExpired("amazon-bedrock"), errorOf(clocked, request(user("Hi")), signed))
        assertEquals(ProviderError.SignInExpired("amazon-bedrock"), errorOf(clocked, request(user("Hi")), signed))
        assertEquals(4, bench.server.requestCount)
    }

    @Test
    fun theOtherTokenErrorsAndA400AreTheCredentialsRefusal() {
        val signed = bedrock.copy(auth = SigV4Auth(Credential.AwsAccessKeys("AKIDEXAMPLE", "secret"), "us-east-1", clock = { 0L }))
        bench.status(403, """{"message":"The security token included in the request is expired"}""", "x-amzn-ErrorType" to "ExpiredTokenException")
        bench.status(403, SIGNATURE_EXPIRED, "x-amzn-ErrorType" to "InvalidSignatureException")
        bench.status(400, """{"message":"The security token included in the request is expired"}""", "x-amzn-ErrorType" to "ExpiredTokenException")
        bench.status(400, """{"message":"Malformed input request"}""", "x-amzn-ErrorType" to "ValidationException")

        assertEquals(ProviderError.SignInExpired("amazon-bedrock"), errorOf(adapter, request(user("Hi")), signed))
        assertEquals(ProviderError.SignInExpired("amazon-bedrock"), errorOf(adapter, request(user("Hi")), signed))
        assertEquals(ProviderError.Unauthorized, errorOf(adapter, request(user("Hi")), bedrock))
        assertEquals(ProviderError.BadRequest(400, "ValidationException"), errorOf(adapter, request(user("Hi")), bedrock))
    }

    @Test
    fun aModelOtherThanClaudeKeepsNoReasoningAndOnlyClaudeAndNovaGetTheFailedStatus() = runBlocking {
        val deepSeek = bedrock.copy(model = ModelEntry("deepseek.r1-v1:0", "DeepSeek R1"), sentModelId = "deepseek.r1-v1:0")
        val nova = bedrock.copy(model = ModelEntry("amazon.nova-pro-v1:0", "Nova Pro"), sentModelId = "amazon.nova-pro-v1:0")
        bench.reply("bedrock/thinking-tool-call.json")
        bench.reply("bedrock/text.json")
        bench.reply("bedrock/text.json")

        val first = adapter.complete(request(user("Open the camera.")), deepSeek)
        val failed = request(user("Open the camera."), first.message, ChatMessage.ToolResult("tooluse_t", "open_app", "Not run.", failed = true))
        adapter.complete(failed, deepSeek)
        adapter.complete(failed, nova)

        assertEquals(listOf(AssistantPart.ToolCall("tooluse_t", "open_app", obj("""{"name":"Camera"}"""))), first.message.parts)
        bench.take()
        val toDeepSeek = bench.body(bench.take())["messages"]!!.jsonArray
        assertEquals(json("""[{"toolUse":{"toolUseId":"tooluse_t","name":"open_app","input":{"name":"Camera"}}}]"""), toDeepSeek[1].jsonObject["content"])
        assertEquals(json("""[{"toolResult":{"toolUseId":"tooluse_t","content":[{"text":"Not run."}]}}]"""), toDeepSeek[2].jsonObject["content"])
        val toNova = bench.body(bench.take())["messages"]!!.jsonArray
        assertEquals(json(""""error""""), toNova[2].jsonObject["content"]!!.jsonArray[0].jsonObject["toolResult"]!!.jsonObject["status"])
    }

    @Test
    fun theStopReasonsAreMappedAndAMalformedToolUseIsAnInvalidCall() {
        val cases = listOf(
            "max_tokens" to StopReason.MAX_TOKENS,
            "model_context_window_exceeded" to StopReason.MAX_TOKENS,
            "guardrail_intervened" to StopReason.FILTERED,
            "content_filtered" to StopReason.FILTERED,
            "malformed_tool_use" to StopReason.OTHER,
            "malformed_model_output" to StopReason.OTHER,
        )
        for ((reason, _) in cases) {
            bench.status(200, """{"output":{"message":{"role":"assistant","content":[{"text":"Partly."}]}},"stopReason":"$reason","usage":{"inputTokens":1,"outputTokens":1}}""")
        }

        for ((reason, stop) in cases) {
            val response = runBlocking { adapter.complete(request(user("Hi")), bedrock) }
            assertEquals(reason, stop, response.stop)
            val invalid = if (reason.startsWith("malformed_")) InvalidKind.BAD_JSON else null
            assertEquals(reason, invalid, response.invalidToolCall?.kind)
        }
    }

    @Test
    fun aClaudeModelThatTakesOnlyItsOwnSamplingGetsNoTemperatureButKeepsAForcedChoice() = runBlocking {
        bench.reply("bedrock/text.json")
        val opus47 = bedrock.copy(model = ModelEntry("anthropic.claude-opus-4-7", "Claude Opus 4.7"), sentModelId = "anthropic.claude-opus-4-7")

        adapter.complete(request(user("Hi")) { copy(toolChoice = ToolChoice.REQUIRED, temperature = 0.2) }, opus47)

        val body = bench.body(bench.take())
        assertNull(body["inferenceConfig"]!!.jsonObject["temperature"])
        assertEquals(json("""{"any":{}}"""), body["toolConfig"]!!.jsonObject["toolChoice"])
    }

    @Test
    fun toolUseIdsThatConverseRefusesAreRewrittenAndStillPair() = runBlocking {
        bench.reply("bedrock/text.json")
        val long = "call_" + "a".repeat(70)
        val odd = "call|1/ä"
        val fits = "tooluse.x:1"
        val calls = listOf(long, odd, fits).map { AssistantPart.ToolCall(it, "open_app", obj("""{"name":"Camera"}""")) }

        adapter.complete(
            request(
                user("Open the camera."),
                ChatMessage.Assistant(calls),
                ChatMessage.ToolResult(long, "open_app", "Opened."),
                ChatMessage.ToolResult(odd, "open_app", "Opened."),
                ChatMessage.ToolResult(fits, "open_app", "Opened."),
            ),
            bedrock,
        )

        val messages = bench.body(bench.take())["messages"]!!.jsonArray
        val used = messages[1].jsonObject["content"]!!.jsonArray.map { it.jsonObject["toolUse"]!!.jsonObject["toolUseId"]!!.jsonPrimitive.content }
        val answered = messages[2].jsonObject["content"]!!.jsonArray.map { it.jsonObject["toolResult"]!!.jsonObject["toolUseId"]!!.jsonPrimitive.content }
        assertEquals(used, answered)
        assertEquals(3, used.toSet().size)
        assertEquals(fits, used[2])
        assertTrue(used.toString(), used.all { Regex("^[a-zA-Z0-9_.:-]{1,64}$").matches(it) })
    }

    private companion object {
        val NOW = Instant.parse("2026-11-19T10:00:00Z").toEpochMilli()
        const val SIGNATURE_EXPIRED = """{"message":"Signature expired"}"""

        fun httpDate(millis: Long): String = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC))
    }
}
