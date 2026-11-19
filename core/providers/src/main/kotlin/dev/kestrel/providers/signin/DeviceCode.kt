package dev.kestrel.providers.signin

import dev.kestrel.providers.http.HttpReply
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp

/**
 * What the sign-in page shows while a device code waits (design D9): the code in large type, the address that the owner
 * opens, the time when the code expires and the poll interval. The code lets anyone approve the sign-in, so toString
 * prints only its length.
 */
data class DeviceCodePrompt(val userCode: String, val verificationUrl: String, val expiresAtMillis: Long, val intervalMillis: Long) {
    override fun toString(): String =
        "DeviceCodePrompt(userCodeLength=${userCode.length}, expiresAtMillis=$expiresAtMillis, intervalMillis=$intervalMillis)"
}

/**
 * A sign-in with a device code (design D9, RFC 8628): [start] asks for a code, and [await] polls until the owner approves
 * or refuses, or the code expires. A flow is used once. [signInHosts] are the hosts that the sign-in needs while it runs;
 * the caller leases them on the allowlist and closes the lease when the flow ends.
 */
interface DeviceCodeFlow {
    val signInHosts: Set<String>

    suspend fun start(): DeviceCodePrompt

    suspend fun await(prompt: DeviceCodePrompt): SignInResult
}

/**
 * The polls of the device flows. A rate limit, a server error, a network failure or a timeout does not end a sign-in: the
 * flow polls more slowly, as after `slow_down` and as RFC 8628 section 3.5 asks after a connection timeout, until the
 * code expires.
 */
internal object DevicePolls {
    const val SLOW_DOWN_MILLIS = 5_000L

    /** The reply to [request], or null when the flow should poll again later; a rate limit's `Retry-After` goes to [later]. */
    suspend fun send(http: ProviderHttp, request: HttpRequest, later: (retryAfterMillis: Long?) -> Unit): HttpReply? {
        val reply = try {
            http.exchange(request)
        } catch (e: ProviderException) {
            if (e.error != ProviderError.Unreachable && e.error != ProviderError.Timeout) throw e
            later(null)
            return null
        }
        if (!SignInHttp.temporary(reply.status)) return reply
        later(if (reply.status == 429) http.retryAfterMillis(reply.headers["Retry-After"]) else null)
        return null
    }

    /** The interval after `slow_down` or a failed poll: 5 s longer, or a longer wait that the server asked for. */
    fun slower(intervalMillis: Long, askedMillis: Long? = null): Long = maxOf(intervalMillis + SLOW_DOWN_MILLIS, askedMillis ?: 0L)
}
