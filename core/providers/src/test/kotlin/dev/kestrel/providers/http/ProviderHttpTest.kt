package dev.kestrel.providers.http

import dev.kestrel.providers.testing.RedirectingCalls
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

class ProviderHttpTest {
    private val server = MockWebServer()
    private lateinit var calls: RedirectingCalls
    private val url = "https://api.example.com/v1/chat/completions?api-version=1".toHttpUrl()

    @Before
    fun start() {
        server.start()
        calls = RedirectingCalls(server)
    }

    @After
    fun stop() {
        server.close()
    }

    private fun post(body: String = """{"model":"m"}""") =
        HttpRequest("POST", url, mapOf("content-type" to "application/json"), body.encodeToByteArray(), "application/json")

    private fun reply(code: Int, body: String = "", vararg headers: Pair<String, String>) =
        MockResponse.Builder().code(code).body(body).apply { headers.forEach { addHeader(it.first, it.second) } }.build()

    /** A token that the test refreshes, with the result that [refreshResult] gives. */
    private class TestAuth(
        var token: String = "old-token",
        private val refreshResult: RefreshResult = RefreshResult.Refreshed,
        override val rejected: ProviderError = ProviderError.SignInExpired("test-provider"),
    ) : RequestAuth {
        var refreshes = 0

        override suspend fun headers(request: HttpRequest) = mapOf("Authorization" to "Bearer $token")

        override suspend fun refresh(): RefreshResult {
            refreshes++
            if (refreshResult == RefreshResult.Refreshed) token = "new-token"
            return refreshResult
        }
    }

    private fun errorOf(block: suspend () -> Unit): ProviderError =
        try {
            runBlocking { block() }
            fail("expected a ProviderException")
            throw AssertionError()
        } catch (e: ProviderException) {
            e.error
        }

    @Test
    fun sendReturnsTheReplyAndKeepsTheFixedAddress() = runBlocking {
        server.enqueue(reply(200, "ok"))

        val reply = ProviderHttp(calls).send(post(), ApiKeyAuth("test-key", KeyHeader.BEARER))

        assertEquals(200, reply.status)
        assertEquals("ok", reply.text())
        assertEquals(listOf(url), calls.requests)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("Bearer test-key", recorded.headers["Authorization"])
        assertEquals("""{"model":"m"}""", recorded.body?.utf8())
        assertEquals("/v1/chat/completions?api-version=1", recorded.target)
    }

    @Test
    fun theDecoratorSeesEveryRequestWithItsMeta() = runBlocking {
        server.enqueue(reply(200))
        val http = ProviderHttp(calls, decorator = { builder, meta -> builder.header("X-Task", meta.taskId ?: "none") })
        val request = HttpRequest("GET", url, meta = RequestMeta(taskId = "task-7"))

        http.send(request, NoAuth)

        assertEquals("task-7", server.takeRequest().headers["X-Task"])
    }

    @Test
    fun a401IsRefreshedOnceAndSentOnceMore() = runBlocking {
        server.enqueue(reply(401))
        server.enqueue(reply(200, "second"))
        val auth = TestAuth()

        val reply = ProviderHttp(calls).send(post(), auth)

        assertEquals("second", reply.text())
        assertEquals(1, auth.refreshes)
        assertEquals("Bearer old-token", server.takeRequest().headers["Authorization"])
        assertEquals("Bearer new-token", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun aSecond401GivesTheRejectedError() {
        server.enqueue(reply(401))
        server.enqueue(reply(401))
        val auth = TestAuth()

        assertEquals(ProviderError.SignInExpired("test-provider"), errorOf { ProviderHttp(calls).send(post(), auth) })
        assertEquals(1, auth.refreshes)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aRefreshThatFailsForNowGivesItsErrorWithItsRetryAfter() {
        server.enqueue(reply(401))
        val auth = TestAuth(refreshResult = RefreshResult.FailedForNow(ProviderError.RateLimited(2_000, "slow_down")))

        assertEquals(ProviderError.RateLimited(2_000, "slow_down"), errorOf { ProviderHttp(calls).send(post(), auth) })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun anExpiredRefreshOrAKeyGivesTheRejectedError() {
        server.enqueue(reply(401))
        server.enqueue(reply(401))

        assertEquals(
            ProviderError.SignInExpired("test-provider"),
            errorOf { ProviderHttp(calls).send(post(), TestAuth(refreshResult = RefreshResult.Expired)) },
        )
        assertEquals(ProviderError.Unauthorized, errorOf { ProviderHttp(calls).send(post(), ApiKeyAuth("test-key", KeyHeader.X_API_KEY)) })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun a403NeverRefreshesAndKeepsItsCode() {
        server.enqueue(reply(403, """{"error": {"code": "model_not_allowed", "message": "Not for you"}}"""))
        val auth = TestAuth()

        assertEquals(ProviderError.QuotaOrPolicy(403, "model_not_allowed"), errorOf { ProviderHttp(calls).send(post(), auth) })
        assertEquals(0, auth.refreshes)
    }

    @Test
    fun a429KeepsItsCodeAndItsRetryAfterInSecondsOrAsADate() {
        server.enqueue(reply(429, """{"type": "error", "error": {"type": "rate_limit_error"}}""", "Retry-After" to "7"))
        server.enqueue(reply(429, "", "Retry-After" to "Tue, 17 Nov 2026 10:00:05 GMT"))
        val now = java.time.Instant.parse("2026-11-17T10:00:00Z").toEpochMilli()
        val http = ProviderHttp(calls, clock = { now })

        assertEquals(ProviderError.RateLimited(7_000, "rate_limit_error"), errorOf { http.send(post(), NoAuth) })
        assertEquals(ProviderError.RateLimited(5_000, null), errorOf { http.send(post(), NoAuth) })
    }

    @Test
    fun otherStatusesKeepTheirStatusAndCode() {
        server.enqueue(reply(402, """{"error": {"type": "insufficient_quota"}}"""))
        server.enqueue(reply(500, """{"error": {"code": "internal"}}"""))
        server.enqueue(reply(400, """{"error": {"type": "invalid_request_error"}}"""))
        server.enqueue(reply(302, "", "Location" to "https://elsewhere.example.com/"))
        val http = ProviderHttp(calls)

        assertEquals(ProviderError.QuotaOrPolicy(402, "insufficient_quota"), errorOf { http.send(post(), NoAuth) })
        assertEquals(ProviderError.ServerError(500), errorOf { http.send(post(), NoAuth) })
        assertEquals(ProviderError.BadRequest(400, "invalid_request_error"), errorOf { http.send(post(), NoAuth) })
        assertEquals(ProviderError.Malformed, errorOf { http.send(post(), NoAuth) })
    }

    @Test
    fun exchangeReturnsAnErrorStatusWithItsBody() = runBlocking {
        server.enqueue(reply(400, """{"error": "authorization_pending"}"""))

        val reply = ProviderHttp(calls).exchange(HttpRequest("POST", "https://github.com/login/oauth/access_token".toHttpUrl()))

        assertEquals(400, reply.status)
        assertEquals("authorization_pending", ErrorCodes.from(reply))
    }

    @Test
    fun aKeyWithAControlCharacterGivesBadCredentialWithoutTheKey() {
        val error = try {
            runBlocking { ProviderHttp(calls).send(post(), ApiKeyAuth("secret\nkey", KeyHeader.X_API_KEY)) }
            null
        } catch (e: ProviderException) {
            e
        }

        assertEquals(ProviderError.BadCredential, error?.error)
        assertFalse(error.toString(), error.toString().contains("secret"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aStreamDeliversEachEventAndIsRetriedAfterARefresh() = runBlocking {
        server.enqueue(reply(401))
        server.enqueue(
            MockResponse.Builder().addHeader("Content-Type", "text/event-stream")
                .body("event: delta\ndata: {\"text\":\"Hel\"}\n\ndata: [DONE]\n\n").build(),
        )
        val events = mutableListOf<SseEvent>()
        val auth = TestAuth()

        ProviderHttp(calls).stream(post(), auth) { events += it }

        assertEquals(listOf(SseEvent("delta", """{"text":"Hel"}"""), SseEvent(null, "[DONE]")), events)
        assertEquals(1, auth.refreshes)
    }

    @Test
    fun aProviderExceptionFromOnEventPassesThroughUnchanged() {
        server.enqueue(MockResponse.Builder().body("event: error\ndata: {\"type\":\"overloaded_error\"}\n\n").build())
        val thrown = ProviderException(ProviderError.ServerError(529))

        val caught = try {
            runBlocking { ProviderHttp(calls).stream(post(), NoAuth) { throw thrown } }
            null
        } catch (e: ProviderException) {
            e
        }

        assertTrue(caught === thrown)
    }

    @Test
    fun aCancelledCallerGetsACancellationExceptionAtOnce() = runBlocking {
        server.enqueue(MockResponse.Builder().body("data: never\n\n").headersDelay(30, TimeUnit.SECONDS).build())
        val ended = CompletableDeferred<Throwable?>()
        val job = launch(Dispatchers.Default) {
            try {
                ProviderHttp(calls).stream(post(), NoAuth) { }
                ended.complete(null)
            } catch (e: Throwable) {
                ended.complete(e)
                throw e
            }
        }
        server.takeRequest(5, TimeUnit.SECONDS)
        val started = System.nanoTime()

        job.cancel()
        val thrown = withTimeout(5_000) { ended.await() }

        assertTrue(thrown.toString(), thrown is CancellationException)
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5))
    }

    @Test
    fun aServerThatAnswersTooLateIsATimeoutAndAClosedOneUnreachable() {
        server.enqueue(MockResponse.Builder().body("late").headersDelay(2, TimeUnit.SECONDS).build())

        assertEquals(ProviderError.Timeout, errorOf { ProviderHttp(RedirectingCalls(server, readTimeoutMillis = 300)).send(post(), NoAuth) })

        val closed = MockWebServer().apply { start() }
        val closedUrl = closed.url("/")
        closed.close()
        val toClosedPort = OkHttpClient.Builder().addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(closedUrl).build()) }.build()
        assertEquals(ProviderError.Unreachable, errorOf { ProviderHttp(toClosedPort).send(post(), NoAuth) })
    }

    @Test
    fun classifyTurnsTheGatewaysOwnExceptionIntoItsError() {
        class Refused : IOException("api.example.com is not on the allowlist")
        val refusing = OkHttpClient.Builder().addInterceptor { throw Refused() }.build()
        val http = ProviderHttp(refusing, classify = { if (it is Refused) ProviderError.NotAllowed else null })

        assertEquals(ProviderError.NotAllowed, errorOf { http.send(post(), NoAuth) })
    }
}
