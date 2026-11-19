package dev.kestrel.providers.signin

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * A loopback listener for the JVM tests of the browser flows: [busy] ports are refused, a null port gets port 54321, and
 * the session's callback comes from [answer], which sees the query of the page that the flow opened ([page]). As
 * LoopbackServer does, a callback with another `state` than the one that the flow waits for never arrives.
 */
internal class FakeLoopback : LoopbackListener {
    var busy = emptySet<Int>()
    var page: HttpUrl? = null
    val opened = mutableListOf<Triple<LoopbackAddresses, Int?, String>>()
    var closed = 0
    var answer: (HttpUrl) -> Map<String, String>? = { mapOf("code" to "test-code", "state" to it.queryParameter("state")!!) }

    val open: (String) -> Unit = { page = it.toHttpUrl() }

    override suspend fun open(addresses: LoopbackAddresses, port: Int?, path: String): LoopbackSession? {
        opened += Triple(addresses, port, path)
        if (port in busy) return null
        return object : LoopbackSession {
            override val port: Int = port ?: 54321

            override suspend fun await(expectedState: String, timeoutMillis: Long): LoopbackCallback? {
                val query = answer(checkNotNull(page) { "the flow waits only after it opened the page" }) ?: return null
                // LoopbackServer answers another state with an error page and keeps waiting until its time ends.
                return query.takeIf { it["state"] == expectedState }?.let(::LoopbackCallback)
            }

            override fun close() {
                closed++
            }
        }
    }
}
