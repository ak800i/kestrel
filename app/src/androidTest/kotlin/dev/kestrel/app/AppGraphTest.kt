package dev.kestrel.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.privacy.logs.NetworkOutcome
import dev.kestrel.privacy.network.BlockedRequestException
import dev.kestrel.privacy.settings.SettingKey
import dev.kestrel.safety.SafetySettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

@RunWith(AndroidJUnit4::class)
class AppGraphTest {
    private val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KestrelApplication).graph

    @Test
    fun blockedRequestIsWrittenToTheEncryptedNetworkLog() = runBlocking {
        graph.logs.deleteHistory()
        val request = Request.Builder().url("https://tracker.example.net/collect").build()

        assertThrows(BlockedRequestException::class.java) { graph.network.callFactory.newCall(request).execute() }

        val entry = withTimeout(5_000) {
            var entries = graph.logs.network()
            while (entries.isEmpty()) {
                delay(50)
                entries = graph.logs.network()
            }
            entries.single()
        }
        assertEquals(NetworkOutcome.BLOCKED, entry.event.outcome)
        assertEquals("tracker.example.net", entry.event.host)
    }

    @Test
    fun plainHttpRequestReachesALoopbackServerAndIsLogged() = runBlocking {
        requestLocalServerOverPlainHttp("127.0.0.1")
    }

    @Test
    fun plainHttpRequestReachesALocalNetworkServerAndIsLogged() = runBlocking {
        // Android 17 allows plain HTTP to loopback by default, but not to the local network.
        val address = NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
        assumeTrue("The device has no local-network IPv4 address", address != null)
        requestLocalServerOverPlainHttp(address!!)
    }

    private suspend fun requestLocalServerOverPlainHttp(host: String) {
        graph.logs.deleteHistory()
        val server = MockWebServer()
        server.start(InetAddress.getByName(host), 0)
        try {
            graph.networkPolicy.setLocalServers(listOf(host))
            server.enqueue(MockResponse.Builder().body("hello").build())
            val request = Request.Builder().url("http://$host:${server.port}/v1/models?key=secret").build()

            val response = graph.network.callFactory.newCall(request).execute()

            assertEquals(200 to "hello", response.use { it.code to it.body.string() })
            val entry = withTimeout(5_000) {
                var local = graph.logs.network().firstOrNull { it.event.host == host }
                while (local == null) {
                    delay(50)
                    local = graph.logs.network().firstOrNull { it.event.host == host }
                }
                local
            }
            assertEquals(NetworkOutcome.COMPLETED, entry.event.outcome)
            assertEquals(200, entry.event.status)
            assertEquals("/v1/models", entry.event.path)
        } finally {
            graph.networkPolicy.setLocalServers(emptyList())
            server.close()
        }
    }

    @Test
    fun settingsSurviveTheEncryptedStore() = runBlocking {
        // A test-only key, so that the real safety settings keep test mode on during all testing (D25).
        val key = SettingKey("test.safety", SafetySettings.serializer(), SafetySettings())

        graph.settings.set(key, SafetySettings(testMode = false))

        assertEquals(SafetySettings(testMode = false), graph.settings.get(key))
    }
}
