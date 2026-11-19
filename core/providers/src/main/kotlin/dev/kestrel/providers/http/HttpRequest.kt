package dev.kestrel.providers.http

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Request

/**
 * What the network log and Copilot's headers need to know about a request (design D3, D12 and D16): the task that caused
 * it, the path that the log records instead of the real one, and whether it is an agent's step after the first request.
 */
data class RequestMeta(val taskId: String? = null, val logPath: String? = null, val agentStep: Boolean = false)

/**
 * Adds what the privacy module needs to each OkHttp request. `:app`'s decorator adds the task's and the log path's tags
 * and Kestrel's User-Agent when no other is set (design D1, D4 and D16).
 */
fun interface RequestDecorator {
    fun decorate(builder: Request.Builder, meta: RequestMeta)
}

/** A request that ProviderHttp sends. Its headers and body can hold keys and prompts, so toString prints only sizes. */
class HttpRequest(
    val method: String,
    val url: HttpUrl,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val contentType: String? = null,
    val meta: RequestMeta = RequestMeta(),
) {
    override fun toString(): String = "HttpRequest(method=$method, headers=${headers.size}, bodyBytes=${body?.size ?: 0})"
}

/** A reply with its whole body in memory. */
class HttpReply(val status: Int, val headers: Headers, val body: ByteArray) {
    fun text(): String = body.decodeToString()

    override fun toString(): String = "HttpReply(status=$status, headers=${headers.size}, bodyBytes=${body.size})"
}

/** One server-sent event. Event names are the protocol's own; the data can hold a reply, so only its length is printed. */
data class SseEvent(val event: String?, val data: String) {
    override fun toString(): String = "SseEvent(event=$event, dataLength=${data.length})"
}
