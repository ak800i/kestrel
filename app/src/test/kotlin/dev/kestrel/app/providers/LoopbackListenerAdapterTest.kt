package dev.kestrel.app.providers

import dev.kestrel.privacy.network.LoopbackServer
import dev.kestrel.providers.signin.LoopbackAddresses
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The adapter over `:core:privacy`'s loopback server (design D10), on the JVM with a real server on 127.0.0.1. */
class LoopbackListenerAdapterTest {
    @Test
    fun aSignInCancelledWhileTheServerBindsClosesTheBoundServer() = runBlocking {
        val binding = CountDownLatch(1)
        val release = CountDownLatch(1)
        var bound: LoopbackServer.Session? = null
        val adapter = LoopbackListenerAdapter { ipv6, port, path ->
            binding.countDown()
            release.await(10, TimeUnit.SECONDS)
            LoopbackServer().open(ipv6, port, path).also { bound = it }
        }

        val job = launch(Dispatchers.Default) { adapter.open(LoopbackAddresses.IPV4, null, "/auth/callback") }
        binding.await(10, TimeUnit.SECONDS)
        job.cancel()
        release.countDown()
        job.join()

        // The port is free again: a new server can take it.
        val again = LoopbackServer().open(false, bound!!.port, "/auth/callback")
        assertNotNull(again)
        again!!.close()
    }
}
