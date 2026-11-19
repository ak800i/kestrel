package dev.kestrel.providers.http

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resumeWithException

/** The longest body that ProviderHttp reads into memory; a longer one is Malformed. */
internal const val MAX_BODY_BYTES = 8L * 1024 * 1024

/**
 * Sends the providers' requests through OkHttp's [Call.Factory], which `:app` takes from the network gateway (design D1),
 * so that every request passes the allowlist and reaches the network log. Every function cancels its OkHttp call when its
 * coroutine is cancelled and returns at once, so that "stop" ends a running request at once, also while OkHttp still looks
 * up the host.
 *
 * Failures are classified in this order: (1) a coroutine that is no longer active gets its CancellationException
 * unchanged; (2) a ProviderException passes through; (3) [classify], which `:app` uses for the gateway's
 * BlockedRequestException; (4) an InterruptedIOException is a Timeout; (5) any other IOException is Unreachable. A value
 * of the credential's headers that OkHttp refuses becomes BadCredential without its message, because the message can hold
 * a key; a refused header that the request itself sets is a bug and throws IllegalStateException, also without the
 * message. A body longer than [MAX_BODY_BYTES] (8 MiB), an event line longer than [SseReader.MAX_LINE_BYTES] (1 MiB) or an
 * event whose joined data is longer than [SseReader.MAX_EVENT_BYTES] (8 MiB) is Malformed, and the rest of it is not read,
 * so that a broken or hostile server cannot exhaust the app's memory.
 */
class ProviderHttp(
    private val calls: Call.Factory,
    private val decorator: RequestDecorator = RequestDecorator { _, _ -> },
    private val classify: (IOException) -> ProviderError? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Returns a 2xx reply, or throws ProviderException; a 401 is refreshed and retried once (design D9). */
    suspend fun send(request: HttpRequest, auth: RequestAuth): HttpReply {
        var reply = transport { fetch(request, auth.headers(request)) }
        if (reply.status == 401) {
            refreshOrThrow(auth)
            reply = transport { fetch(request, auth.headers(request)) }
            if (reply.status == 401) throw ProviderException(auth.rejected)
        }
        if (reply.status !in 200..299) throw ProviderException(errorFor(reply))
        return reply
    }

    /** Returns any status with its headers and body, for the sign-in flows and refreshers; only transport failures throw. */
    suspend fun exchange(request: HttpRequest, auth: RequestAuth = NoAuth): HttpReply =
        transport { fetch(request, auth.headers(request)) }

    /**
     * Reads a 2xx body of server-sent events and calls [onEvent] once per event. An error status throws ProviderException
     * as [send] does; an exception that [onEvent] throws ends the stream and reaches the caller.
     */
    suspend fun stream(request: HttpRequest, auth: RequestAuth, onEvent: suspend (SseEvent) -> Unit) {
        var failed = transport { open(request, auth.headers(request), onEvent) }
        if (failed?.status == 401) {
            refreshOrThrow(auth)
            failed = transport { open(request, auth.headers(request), onEvent) }
            if (failed?.status == 401) throw ProviderException(auth.rejected)
        }
        if (failed != null) throw ProviderException(errorFor(failed))
    }

    private suspend fun refreshOrThrow(auth: RequestAuth) {
        when (val result = transport { auth.refresh() }) {
            RefreshResult.Refreshed -> Unit
            is RefreshResult.FailedForNow -> throw ProviderException(result.error)
            RefreshResult.Expired, RefreshResult.NotPossible -> throw ProviderException(auth.rejected)
        }
    }

    private suspend fun fetch(request: HttpRequest, headers: Map<String, String>): HttpReply =
        call(request, headers) { response -> HttpReply(response.code, response.headers, bodyBytes(response)) }

    /** Reads the events of a 2xx reply and returns null, or returns the error reply. */
    private suspend fun open(request: HttpRequest, headers: Map<String, String>, onEvent: suspend (SseEvent) -> Unit): HttpReply? =
        call(request, headers) { response ->
            if (response.code in 200..299) {
                SseReader.read(response.body.source(), onEvent)
                null
            } else {
                HttpReply(response.code, response.headers, bodyBytes(response))
            }
        }

    private suspend fun <T> call(request: HttpRequest, headers: Map<String, String>, read: suspend (Response) -> T): T {
        val built = build(request, headers)
        currentCoroutineContext().ensureActive()
        val call = calls.newCall(built)
        // Waits for the reply's headers without blocking a thread, so that a cancel returns at once, also while OkHttp still
        // looks up the host, which its cancel cannot interrupt; OkHttp's thread then ends the lookup alone and sends nothing.
        val response = suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onResponse(call: Call, response: Response) {
                        continuation.resume(response) { _, value, _ -> value.close() }
                    }

                    override fun onFailure(call: Call, e: IOException) {
                        continuation.resumeWithException(e)
                    }
                },
            )
        }
        return response.use {
            coroutineScope {
                // Cancels the call when the caller's coroutine is cancelled while a blocking read of the body waits.
                val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        call.cancel()
                    }
                }
                try {
                    withContext(Dispatchers.IO) { read(response) }
                } finally {
                    watcher.cancel()
                }
            }
        }
    }

    /** Reads a whole body of at most [MAX_BODY_BYTES]; a longer one is Malformed, and its rest is not read. */
    private fun bodyBytes(response: Response): ByteArray {
        val body = response.body
        if (body.contentLength() > MAX_BODY_BYTES) throw ProviderException(ProviderError.Malformed)
        val source = body.source()
        if (source.request(MAX_BODY_BYTES + 1)) throw ProviderException(ProviderError.Malformed)
        return source.readByteArray()
    }

    private fun build(request: HttpRequest, headers: Map<String, String>): Request {
        val builder = Request.Builder().url(request.url)
        try {
            for ((name, value) in request.headers - headers.keys) builder.header(name, value)
        } catch (e: IllegalArgumentException) {
            // A header that an adapter sets is a bug, not a bad key. OkHttp's message contains the value, which can name the
            // owner's account, so it is not kept.
            throw IllegalStateException("A header that the request sets is not valid")
        }
        try {
            for ((name, value) in headers) builder.header(name, value)
        } catch (e: IllegalArgumentException) {
            // OkHttp's message contains the value, which can be a key.
            throw ProviderException(ProviderError.BadCredential)
        }
        val body = request.body?.toRequestBody(request.contentType?.toMediaTypeOrNull())
            ?: if (request.method in BODY_METHODS) ByteArray(0).toRequestBody(null) else null
        builder.method(request.method, body)
        decorator.decorate(builder, request.meta)
        return builder.build()
    }

    private suspend fun <T> transport(block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProviderException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw ProviderException(classify(e) ?: if (e is InterruptedIOException) ProviderError.Timeout else ProviderError.Unreachable)
        }

    private fun errorFor(reply: HttpReply): ProviderError {
        val code = ErrorCodes.from(reply)
        return when (reply.status) {
            402, 403 -> ProviderError.QuotaOrPolicy(reply.status, code)
            429 -> ProviderError.RateLimited(retryAfterMillis(reply.headers["Retry-After"]), code)
            in 500..599 -> ProviderError.ServerError(reply.status)
            in 400..499 -> ProviderError.BadRequest(reply.status, code)
            else -> ProviderError.Malformed
        }
    }

    /** `Retry-After` in seconds or as an HTTP date (RFC 9110 §10.2.3). */
    private fun retryAfterMillis(value: String?): Long? {
        val text = value?.trim() ?: return null
        text.toLongOrNull()?.let { return if (it >= 0) it.coerceAtMost(Long.MAX_VALUE / 1000) * 1000 else null }
        return try {
            (ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - clock()).coerceAtLeast(0)
        } catch (e: DateTimeParseException) {
            null
        }
    }

    private companion object {
        val BODY_METHODS = setOf("POST", "PUT", "PATCH")
    }
}
