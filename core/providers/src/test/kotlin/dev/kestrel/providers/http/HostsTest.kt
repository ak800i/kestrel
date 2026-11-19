package dev.kestrel.providers.http

import org.junit.Assert.assertEquals
import org.junit.Test

class HostsTest {
    @Test
    fun localAndLoopbackHosts() {
        val table = listOf(
            // host, local, loopback
            Triple("localhost", true, true),
            Triple("127.0.0.1", true, true),
            Triple("127.8.9.10", true, true),
            Triple("[::1]", true, true),
            Triple("192.168.1.20", true, false),
            Triple("10.0.0.2", true, false),
            Triple("172.16.0.1", true, false),
            Triple("172.32.0.1", false, false),
            Triple("169.254.3.4", true, false),
            Triple("fd12::1", true, false),
            Triple("fe80::1", true, false),
            Triple("studio.local", true, false),
            Triple("127.0.0.1.", true, true),
            Triple("localhost.", true, true),
            Triple("api.openai.com", false, false),
            Triple("8.8.8.8", false, false),
            Triple("256.1.1.1", false, false),
        )

        for ((host, local, loopback) in table) {
            assertEquals(host, local, Hosts.isLocal(host))
            assertEquals(host, loopback, Hosts.isLoopback(host))
        }
    }
}
