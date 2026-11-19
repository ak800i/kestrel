package dev.kestrel.app.providers

import dev.kestrel.privacy.network.LocalHosts
import dev.kestrel.providers.http.Hosts
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `:core:providers` copies `:core:privacy`'s LocalHosts rules, because it may not use InetAddress (design spec, section
 * 13.2). This table keeps the two from drifting: a host that one calls local and the other public would be allowed over
 * plain HTTP by one and refused by the other.
 */
class HostsAgreementTest {
    @Test
    fun bothRulesAgreeOnEveryHost() {
        val hosts = listOf(
            "localhost", "LOCALHOST", "printer.local", "local", "example.local.net",
            "127.0.0.1", "127.255.255.254", "10.0.0.1", "10.255.0.1", "172.15.0.1", "172.16.0.1", "172.31.255.255", "172.32.0.1",
            "192.168.0.1", "192.169.0.1", "169.254.1.1", "169.255.1.1", "8.8.8.8", "256.0.0.1", "1.2.3", "1.2.3.4.5", "01.2.3.4",
            "::1", "[::1]", "fc00::1", "fd12:3456::1", "fe80::1", "fe90::1", "2001:db8::1", "[2001:db8::1]",
            "api.openai.com", "cli-chat-proxy.grok.com", "openrouter.ai",
        )

        for (host in hosts) {
            assertEquals(host, LocalHosts.isLocal(host), Hosts.isLocal(host))
        }
    }
}
