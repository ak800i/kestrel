package dev.kestrel.providers.testing

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Fails the calls with the numbers in [failing], counted from 1, as a lost connection, and sends the others through [inner]. */
class FailingCalls(private val inner: Call.Factory, private val failing: Set<Int>) : Call.Factory {
    private val count = AtomicInteger()

    override fun newCall(request: Request): Call {
        val real = inner.newCall(request)
        if (count.incrementAndGet() !in failing) return real
        return object : Call by real {
            override fun enqueue(responseCallback: Callback) = responseCallback.onFailure(this, IOException("test connection lost"))
        }
    }
}
