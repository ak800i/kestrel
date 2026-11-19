package dev.kestrel.app.providers

import dev.kestrel.privacy.network.LoopbackServer
import dev.kestrel.providers.signin.LoopbackAddresses
import dev.kestrel.providers.signin.LoopbackCallback
import dev.kestrel.providers.signin.LoopbackListener
import dev.kestrel.providers.signin.LoopbackSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * `:core:providers`' loopback listener over `:core:privacy`'s LoopbackServer, which alone may open a socket (design D1
 * and D10). A cancelled wait closes the server, so that its blocking wait ends at once, and so does a sign-in that was
 * cancelled while the server bound its port, so that the port is free again at once.
 */
internal class LoopbackListenerAdapter(
    private val bind: (ipv6: Boolean, port: Int?, path: String) -> LoopbackServer.Session? = LoopbackServer()::open,
) : LoopbackListener {
    override suspend fun open(addresses: LoopbackAddresses, port: Int?, path: String): LoopbackSession? {
        val bound = AtomicReference<LoopbackServer.Session?>()
        try {
            // The bind always finishes. withContext drops its result when the sign-in was cancelled meanwhile, so the server
            // is kept here and not as withContext's result.
            withContext(NonCancellable + Dispatchers.IO) { bound.set(bind(addresses == LoopbackAddresses.BOTH, port, path)) }
        } catch (e: CancellationException) {
            bound.get()?.close()
            throw e
        }
        return bound.get()?.let(::Session)
    }

    private class Session(private val session: LoopbackServer.Session) : LoopbackSession {
        override val port: Int = session.port

        override suspend fun await(expectedState: String, timeoutMillis: Long): LoopbackCallback? = coroutineScope {
            // Started at once, so that a cancellation before it runs still reaches its finally and closes the server.
            val closer = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    session.close()
                }
            }
            try {
                withContext(Dispatchers.IO) { session.await(expectedState, timeoutMillis) }?.let(::LoopbackCallback)
            } finally {
                closer.cancel()
            }
        }

        override fun close() = session.close()
    }
}
