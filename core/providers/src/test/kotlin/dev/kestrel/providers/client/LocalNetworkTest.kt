package dev.kestrel.providers.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkTest {
    @Test
    fun onlyAHostOnTheOwnersNetworkNeedsThePermission() {
        assertTrue(LocalNetwork.needsPermission("192.168.1.20"))
        assertTrue(LocalNetwork.needsPermission("studio.local"))
        assertTrue(LocalNetwork.needsPermission("fd00::1"))
        assertFalse(LocalNetwork.needsPermission("127.0.0.1"))
        assertFalse(LocalNetwork.needsPermission("localhost"))
        assertFalse(LocalNetwork.needsPermission("::1"))
        assertFalse(LocalNetwork.needsPermission("api.openai.com"))
    }
}
