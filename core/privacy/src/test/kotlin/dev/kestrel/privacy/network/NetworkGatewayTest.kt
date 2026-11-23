package dev.kestrel.privacy.network

import dev.kestrel.privacy.logs.NetworkEvent
import dev.kestrel.privacy.logs.NetworkOutcome
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

class NetworkGatewayTest {
    private val server = MockWebServer()
    private val events = CopyOnWriteArrayList<NetworkEvent>()
    private val policy = NetworkPolicy()
    private val gateway = NetworkGateway(policy) { events += it }

    @Before
    fun startServer() {
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun stopServer() {
        server.close()
    }

    private fun serverUrl(path: String): HttpUrl = server.url(path).newBuilder().host("127.0.0.1").build()

    private fun execute(request: Request): Pair<Int, String> =
        gateway.callFactory.newCall(request).execute().use { it.code to it.body.string() }

    @Test
    fun logsAllowedRequestsWithoutTheQuery() {
        policy.setLocalServers(listOf("127.0.0.1"))
        server.enqueue(MockResponse.Builder().body("hello").build())

        val request = Request.Builder().url(serverUrl("/v1/models?key=secret")).tag(TaskTag::class.java, TaskTag("task-1")).build()

        assertEquals(200 to "hello", execute(request))
        assertEquals(
            NetworkEvent(
                method = "GET",
                host = "127.0.0.1",
                path = "/v1/models",
                requestBytes = 0,
                responseBytes = 5,
                status = 200,
                outcome = NetworkOutcome.COMPLETED,
                taskId = "task-1",
            ),
            events.single(),
        )
    }

    @Test
    fun countsRequestBodyBytes() {
        policy.setLocalServers(listOf("127.0.0.1"))
        server.enqueue(MockResponse.Builder().body("ok").build())

        execute(Request.Builder().url(serverUrl("/v1/chat")).post("hello".toRequestBody()).build())

        assertEquals(5L, events.single().requestBytes)
    }

    @Test
    fun blocksHostsOutsideTheAllowlistBeforeConnecting() {
        val request = Request.Builder().url("https://tracker.example.net/collect".toHttpUrl()).build()

        val error = assertThrows(BlockedRequestException::class.java) { execute(request) }

        assertEquals("tracker.example.net is not on the allowlist", error.message)
        val event = events.single()
        assertEquals(NetworkOutcome.BLOCKED, event.outcome)
        assertEquals("tracker.example.net is not on the allowlist", event.detail)
    }

    @Test
    fun blocksPlainHttpUnlessTheServerIsALocalServer() {
        server.enqueue(MockResponse.Builder().body("never sent").build())

        assertThrows(BlockedRequestException::class.java) { execute(Request.Builder().url(serverUrl("/")).build()) }

        assertEquals(0, server.requestCount)
    }

    @Test
    fun doesNotFollowRedirects() {
        policy.setLocalServers(listOf("127.0.0.1"))
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", serverUrl("/elsewhere").toString()).build())

        val (code, _) = execute(Request.Builder().url(serverUrl("/start")).build())

        assertEquals(302, code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun logsTheTaggedPathWithoutItsQueryInsteadOfTheRealOne() {
        policy.setLocalServers(listOf("127.0.0.1"))
        server.enqueue(MockResponse.Builder().body("ok").build())
        val request = Request.Builder()
            .url(serverUrl("/v1/projects/test-project/locations/global/publishers/google/models/m:streamGenerateContent?alt=sse"))
            .tag(LogPathTag::class.java, LogPathTag("/v1/projects/{project}/locations/global/publishers/google/models/m:streamGenerateContent?alt=sse"))
            .build()

        execute(request)

        assertEquals("/v1/projects/{project}/locations/global/publishers/google/models/m:streamGenerateContent", events.single().path)
    }

    @Test
    fun aFailedRequestIsLoggedByTheClassOfItsExceptionNotByItsMessage() {
        policy.setLocalServers(listOf("127.0.0.1"))
        val url = serverUrl("/v1/models?key=secret")
        server.close()

        assertThrows(IOException::class.java) { execute(Request.Builder().url(url).build()) }

        val event = events.single()
        assertEquals(NetworkOutcome.FAILED, event.outcome)
        assertEquals("ConnectException", event.detail)
    }

    @Test
    fun aCallThatAnUncheckedExceptionEndsWithoutAStatusIsLoggedAsFailed() {
        policy.setLocalServers(listOf("127.0.0.1"))
        // OkHttp asks for the length before it connects; an unchecked exception ends the call in callEnd, not callFailed.
        val body = object : RequestBody() {
            override fun contentType() = null

            override fun contentLength(): Long = throw IllegalStateException("test")

            override fun writeTo(sink: BufferedSink) = Unit
        }

        assertThrows(IllegalStateException::class.java) { execute(Request.Builder().url(serverUrl("/v1/chat")).post(body).build()) }

        val event = events.single()
        assertEquals(NetworkOutcome.FAILED, event.outcome)
        assertEquals(null, event.status)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aModelDownloadIsLoggedWithAFixedPath() {
        policy.setLocalServers(listOf("127.0.0.1"))
        server.enqueue(MockResponse.Builder().body("model").build())

        val request = Request.Builder()
            .url(serverUrl("/xet-bridge-us/abc?signature=secret"))
            .tag(ModelDownloadTag::class.java, ModelDownloadTag)
            .build()

        assertEquals(200 to "model", execute(request))
        assertEquals(ModelDownloadTag.LOG_PATH, events.single().path)
    }

    @Test
    fun aRequestWithoutTheModelDownloadTagCannotReachAModelHost() {
        policy.setModelHosts(listOf("models.example.org"))

        val error = assertThrows(BlockedRequestException::class.java) {
            execute(Request.Builder().url("https://models.example.org/a.onnx".toHttpUrl()).build())
        }

        assertEquals("models.example.org is not on the allowlist", error.message)
        assertEquals(NetworkOutcome.BLOCKED, events.single().outcome)
    }
}
