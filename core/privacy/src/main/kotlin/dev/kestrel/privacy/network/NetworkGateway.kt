package dev.kestrel.privacy.network

import dev.kestrel.privacy.logs.NetworkEvent
import dev.kestrel.privacy.logs.NetworkOutcome
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** Tags a request with the task that caused it, so that the network log can show it. */
data class TaskTag(val taskId: String)

/**
 * Tags a request with the path that the network log records instead of the real one, because a path can name the owner's
 * project or account, such as Vertex's `/v1/projects/<project>/…` or a Bedrock inference-profile ARN (design spec, section
 * 13.5).
 */
data class LogPathTag(val path: String)

class BlockedRequestException(val url: HttpUrl, reason: String) : IOException(reason)

/**
 * The only HTTP client in Kestrel (design spec, section 13.1). It refuses hosts outside the allowlist
 * before any DNS lookup or connection, never follows redirects on its own, ignores system proxies and
 * reports every request to [record] for the network log.
 */
class NetworkGateway(
    policy: NetworkPolicy,
    record: (NetworkEvent) -> Unit,
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(AllowlistInterceptor(policy))
        .eventListenerFactory { NetworkLogListener(record) }
        .build()

    /** Every request in Kestrel goes through this. */
    val callFactory: Call.Factory get() = client
}

private class AllowlistInterceptor(private val policy: NetworkPolicy) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        return when (val decision = policy.check(url)) {
            PolicyDecision.Allowed -> chain.proceed(chain.request())
            is PolicyDecision.Blocked -> throw BlockedRequestException(url, decision.reason)
        }
    }
}

private class NetworkLogListener(private val record: (NetworkEvent) -> Unit) : EventListener() {
    private var requestBytes = 0L
    private var responseBytes = 0L
    private var status: Int? = null

    override fun requestBodyEnd(call: Call, byteCount: Long) {
        requestBytes += byteCount
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        status = response.code
    }

    override fun responseBodyEnd(call: Call, byteCount: Long) {
        responseBytes += byteCount
    }

    override fun callEnd(call: Call) = write(call, NetworkOutcome.COMPLETED, detail = null)

    override fun callFailed(call: Call, ioe: IOException) {
        val blocked = ioe is BlockedRequestException
        val outcome = if (blocked) NetworkOutcome.BLOCKED else NetworkOutcome.FAILED
        // OkHttp's messages can hold a URL, so a failure is logged by its class; the allowlist's own message names only the
        // host and tells "not on the allowlist" from "HTTPS is required".
        write(call, outcome, if (blocked) ioe.message else ioe.javaClass.simpleName)
    }

    private fun write(call: Call, outcome: NetworkOutcome, detail: String?) {
        val request = call.request()
        record(
            NetworkEvent(
                method = request.method,
                host = request.url.host,
                path = request.tag(LogPathTag::class.java)?.path?.substringBefore('?') ?: request.url.encodedPath,
                requestBytes = requestBytes,
                responseBytes = responseBytes,
                status = status,
                outcome = outcome,
                taskId = request.tag(TaskTag::class.java)?.taskId,
                detail = detail,
            ),
        )
    }
}
