package dev.kestrel.privacy.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalHostsTest {
    @Test
    fun acceptsLocalhostAndPrivateNetworks() {
        listOf(
            "localhost", "127.0.0.1", "10.0.0.5", "172.16.0.1", "172.31.255.255", "192.168.1.10",
            "169.254.1.1", "::1", "[::1]", "fd00::1", "fe80::1", "nas.local",
        ).forEach { assertTrue(it, LocalHosts.isLocal(it)) }
    }

    @Test
    fun rejectsPublicHostsAndMalformedAddresses() {
        listOf(
            "172.32.0.1", "8.8.8.8", "example.com", "1.2.3", "256.1.1.1", "fdroid.org", "2001:db8::1", "localhost.example.com",
        ).forEach { assertFalse(it, LocalHosts.isLocal(it)) }
    }
}
