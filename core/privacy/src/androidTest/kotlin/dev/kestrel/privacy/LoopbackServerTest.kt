package dev.kestrel.privacy

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.privacy.network.LoopbackServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The loopback server of browser sign-in on the device (design D10 and D19): which addresses it binds, how it reports a
 * busy port, and how it answers. Every server that a test opens is closed at its end.
 */
@RunWith(AndroidJUnit4::class)
class LoopbackServerTest {
    private val ipv4 = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val ipv6 = InetAddress.getByAddress(ByteArray(16).also { it[15] = 1 })
    private val held = mutableListOf<AutoCloseable>()
    private val executor = Executors.newSingleThreadExecutor()

    @After
    fun closeAll() {
        held.forEach { it.close() }
        executor.shutdownNow()
    }

    private fun <T : AutoCloseable> T.held(): T = also { held += it }

    /** Sends a GET of [target] to [address]:[port] and returns the status line, or null when nothing listens. */
    private fun get(address: InetAddress, port: Int, target: String): String? =
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(address, port), 2_000)
                socket.soTimeout = 5_000
                socket.getOutputStream().write("GET $target HTTP/1.1\r\nHost: localhost:$port\r\n\r\n".encodeToByteArray())
                socket.getInputStream().bufferedReader().readText().substringBefore("\r\n")
            }
        } catch (e: IOException) {
            null
        }

    @Test
    fun theChatGptPlansRedirectBindsOnly127001AndNeverTheWildcard() {
        val session = opened(LoopbackServer().open(ipv6 = false, port = 1455, path = "/auth/callback")).held()

        assertEquals(1455, session.port)
        assertEquals(listOf(ipv4), session.addresses)
        assertTrue(session.addresses.none { it.isAnyLocalAddress })
        if (LoopbackServer.hasIpv6Loopback()) assertNull(get(ipv6, 1455, "/auth/callback"))
    }

    @Test
    fun openRoutersRedirectBindsBothLoopbackAddressesOnPort3000() {
        assumeTrue("The device has no IPv6 loopback", LoopbackServer.hasIpv6Loopback())

        val session = opened(LoopbackServer().open(ipv6 = true, port = 3000, path = "/openrouter-oauth/callback")).held()

        assertEquals(3000, session.port)
        assertEquals(listOf(ipv4, ipv6), session.addresses)
        assertTrue(session.addresses.none { it.isAnyLocalAddress })
    }

    @Test
    fun aPortHeldOnIpv6OnlyIsBusyForALocalhostRedirectButNotFor127001() {
        assumeTrue("The device has no IPv6 loopback", LoopbackServer.hasIpv6Loopback())
        ServerSocket().apply { bind(InetSocketAddress(ipv6, 3000)) }.held()

        assertNull(LoopbackServer().open(ipv6 = true, port = 3000, path = "/openrouter-oauth/callback"))
        opened(LoopbackServer().open(ipv6 = false, port = 3000, path = "/auth/callback")).held()
    }

    @Test
    fun aBusyPortIsReportedAndANullPortFindsAFreeOne() {
        ServerSocket().apply { bind(InetSocketAddress(ipv4, 1456)) }.held()

        assertNull(LoopbackServer().open(ipv6 = false, port = 1456, path = "/auth/callback"))
        val free = opened(LoopbackServer().open(ipv6 = true, port = null, path = "/auth/callback")).held()
        assertTrue(free.port > 0)
    }

    @Test
    fun otherPathsGet404AWrongStateIsAnsweredAndTheServerKeepsWaitingUntilTheCallback() {
        val session = opened(LoopbackServer().open(ipv6 = false, port = null, path = "/auth/callback")).held()
        val result = executor.submit<Map<String, String>?> { session.await("test-state", 20_000) }

        assertEquals("HTTP/1.1 404 Not Found", get(ipv4, session.port, "/favicon.ico"))
        assertEquals("HTTP/1.1 400 Bad Request", get(ipv4, session.port, "/auth/callback?code=test-code&state=wrong"))
        assertEquals("HTTP/1.1 400 Bad Request", get(ipv4, session.port, "/auth/callback?code=test-code"))
        assertEquals("HTTP/1.1 200 OK", get(ipv4, session.port, "/auth/callback?code=test%2Bcode&state=test-state"))

        assertEquals(mapOf("code" to "test+code", "state" to "test-state"), result.get(5, TimeUnit.SECONDS))
        // The server closed after the callback.
        assertNull(get(ipv4, session.port, "/auth/callback"))
    }

    @Test
    fun theServerClosesAfterItsTimeAndOnClose() {
        val timed = opened(LoopbackServer().open(ipv6 = false, port = null, path = "/auth/callback")).held()
        assertNull(timed.await("test-state", 300))
        assertNull(get(ipv4, timed.port, "/auth/callback"))

        val cancelled = opened(LoopbackServer().open(ipv6 = false, port = null, path = "/auth/callback")).held()
        val result = executor.submit<Map<String, String>?> { cancelled.await("test-state", 60_000) }
        Thread.sleep(300)
        cancelled.close()
        assertNull(result.get(5, TimeUnit.SECONDS))
    }

    private fun opened(session: LoopbackServer.Session?): LoopbackServer.Session {
        org.junit.Assert.assertNotNull("the server did not open", session)
        return session!!
    }
}
