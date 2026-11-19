package dev.kestrel.providers.testing

import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * A Call.Factory for JVM tests that sends every request to [server] and records the original URL (design D1). So the
 * adapters and the sign-in flows keep their fixed `https://` addresses and host checks in the tests, and nobody loosens a
 * check to make a test pass. Test sources are not audited, so this test helper may build an OkHttpClient.
 */
class RedirectingCalls(private val server: MockWebServer, readTimeoutMillis: Long = 10_000) : Call.Factory {
    private val recorded = Collections.synchronizedList(mutableListOf<HttpUrl>())

    /** The URLs that the code under test asked for, in order. */
    val requests: List<HttpUrl> get() = synchronized(recorded) { recorded.toList() }

    private val client = OkHttpClient.Builder()
        .readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .addInterceptor { chain ->
            val original = chain.request().url
            recorded += original
            // The server's own scheme, host and port: resolving the path would read one that starts with "//" as a host.
            val target = server.url("/").newBuilder()
                .encodedPath(original.encodedPath)
                .encodedQuery(original.encodedQuery)
                .build()
            chain.proceed(chain.request().newBuilder().url(target).build())
        }
        .build()

    override fun newCall(request: Request): Call = client.newCall(request)
}
