package dev.kestrel.privacy.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NetworkPolicyTest {
    private val policy = NetworkPolicy()

    @Test
    fun allowsProfileHostsOverHttps() {
        policy.setProfileHosts(listOf("API.Example.com"))

        assertEquals(PolicyDecision.Allowed, policy.check("https://api.example.com/v1/chat".toHttpUrl()))
    }

    @Test
    fun blocksPlainHttpToProfileHosts() {
        policy.setProfileHosts(listOf("api.example.com"))

        assertEquals(
            PolicyDecision.Blocked("HTTPS is required for api.example.com"),
            policy.check("http://api.example.com/".toHttpUrl()),
        )
    }

    @Test
    fun blocksHostsOutsideTheAllowlist() {
        policy.setProfileHosts(listOf("api.example.com"))

        assertEquals(
            PolicyDecision.Blocked("tracker.example.net is not on the allowlist"),
            policy.check("https://tracker.example.net/".toHttpUrl()),
        )
    }

    @Test
    fun allowsLocalServersOverPlainHttp() {
        policy.setLocalServers(listOf("192.168.1.20"))

        assertEquals(PolicyDecision.Allowed, policy.check("http://192.168.1.20:1234/v1/models".toHttpUrl()))
    }

    @Test
    fun refusesPublicHostsAsLocalServers() {
        val error = assertThrows(IllegalArgumentException::class.java) { policy.setLocalServers(listOf("example.com")) }

        assertEquals("example.com is not a local-network or localhost address", error.message)
    }

    @Test
    fun temporaryHostsAreAllowedUntilEveryLeaseCloses() {
        val url = "https://login.example.com/device".toHttpUrl()
        val first = policy.openTemporaryHosts(listOf("login.example.com"))
        val second = policy.openTemporaryHosts(listOf("login.example.com"))

        first.close()
        first.close()
        assertEquals(PolicyDecision.Allowed, policy.check(url))

        second.close()
        assertEquals(PolicyDecision.Blocked("login.example.com is not on the allowlist"), policy.check(url))
    }
}
