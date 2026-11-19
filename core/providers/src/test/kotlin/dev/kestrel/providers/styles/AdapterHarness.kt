package dev.kestrel.providers.styles

import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.chat.ChatAdapter
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.ModelTarget
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.chat.StyleOptions
import dev.kestrel.providers.chat.ToolSpec
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.NoAuth
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.http.RefreshResult
import dev.kestrel.providers.http.RequestAuth
import dev.kestrel.providers.testing.RedirectingCalls
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.fail

/**
 * The shared test bench of the request styles (design D19): a MockWebServer behind [RedirectingCalls], so that each
 * adapter keeps its fixed `https://` address, and the fixtures under `src/test/resources/fixtures/<style>/`.
 */
internal class AdapterHarness : AutoCloseable {
    val server = MockWebServer().apply { start() }
    val calls = RedirectingCalls(server)
    val http = ProviderHttp(calls)

    fun fixture(path: String): String =
        AdapterHarness::class.java.getResource("/fixtures/$path")?.readText()?.replace("\r\n", "\n")
            ?: throw AssertionError("missing fixture $path")

    /** A 200 reply whose body is the fixture [path]: server-sent events for `.sse`, JSON otherwise. */
    fun reply(path: String) {
        val type = if (path.endsWith(".sse")) "text/event-stream" else "application/json"
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", type).body(fixture(path)).build())
    }

    fun status(code: Int, body: String = "", vararg headers: Pair<String, String>) {
        server.enqueue(MockResponse.Builder().code(code).body(body).apply { headers.forEach { addHeader(it.first, it.second) } }.build())
    }

    fun take(): RecordedRequest = server.takeRequest()

    fun body(recorded: RecordedRequest): JsonObject = Json.parseToJsonElement(recorded.body!!.utf8()) as JsonObject

    /** Compares a request body with the fixture [path] as JSON, so that the order of keys does not matter. */
    fun assertBody(path: String, recorded: RecordedRequest) {
        assertEquals(Json.parseToJsonElement(fixture(path)), body(recorded))
    }

    override fun close() {
        server.close()
    }

    companion object {
        val OPEN_APP = ToolSpec(
            "open_app",
            "Opens an app by its name.",
            Json.parseToJsonElement("""{"type":"object","properties":{"name":{"type":"string","description":"The app's name."}},"required":["name"]}""") as JsonObject,
        )
        val GET_NUMBER = ToolSpec("get_number", "Returns the test number.", Json.parseToJsonElement("""{"type":"object"}""") as JsonObject)

        /** Eight bytes that stand for a screenshot; adapters only encode them. */
        val SCREENSHOT = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

        fun json(text: String): JsonElement = Json.parseToJsonElement(text)

        fun obj(text: String): JsonObject = json(text) as JsonObject

        fun user(text: String): ChatMessage.User = ChatMessage.User(listOf(Part.Text(text)))

        fun target(
            providerId: String,
            style: RequestStyle,
            baseUrl: String,
            model: ModelEntry,
            auth: RequestAuth = NoAuth,
            options: StyleOptions = StyleOptions(),
            sentModelId: String = model.id,
        ) = ModelTarget(providerId, style, baseUrl.toHttpUrl(), model, sentModelId, auth, options = options)

        fun request(vararg messages: ChatMessage, tools: List<ToolSpec> = listOf(OPEN_APP), block: ChatRequest.() -> ChatRequest = { this }) =
            ChatRequest(system = "You are Kestrel.", messages = messages.toList(), tools = tools, maxOutputTokens = 256).block()

        fun errorOf(adapter: ChatAdapter, request: ChatRequest, target: ModelTarget): ProviderError =
            try {
                runBlocking { adapter.complete(request, target) }
                fail("expected a ProviderException")
                throw AssertionError()
            } catch (e: ProviderException) {
                e.error
            }
    }

    /** A credential that is refreshed once, as ProviderHttp's rule of one refresh and one retry needs (design D9). */
    class RefreshingAuth(private val header: String = "Authorization") : RequestAuth {
        var token = "old-token"
        var refreshes = 0

        override suspend fun headers(request: HttpRequest) = mapOf(header to "Bearer $token")

        override suspend fun refresh(): RefreshResult {
            refreshes++
            token = "new-token"
            return RefreshResult.Refreshed
        }
    }
}
