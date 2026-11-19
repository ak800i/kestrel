package dev.kestrel.app.providers

import dev.kestrel.privacy.network.LocalHosts
import dev.kestrel.privacy.network.NetworkPolicy
import dev.kestrel.privacy.network.PolicyDecision
import dev.kestrel.providers.catalog.AuthMethod
import dev.kestrel.providers.catalog.CatalogLoader
import dev.kestrel.providers.http.Hosts
import dev.kestrel.providers.profiles.Profile
import dev.kestrel.providers.profiles.ProfileHosts
import dev.kestrel.providers.profiles.ProfileModel
import okhttp3.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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

    @Test
    fun bothRulesReadOnlyPlainAsciiOctetsAndTheWholeUniqueLocalPrefixAndDropATrailingDot() {
        val table = listOf(
            "0127.0.0.1" to false, "127.0.0.01" to false, "+10.0.0.1" to false,
            "\u0661\u0660.0.0.1" to false, "10.0.0.\u0665" to false, "1\uFF10.0.0.1" to false,
            "fc::1" to false, "fd::1" to false, "fc0::1" to false, "[fd1::1]" to false,
            "fc00::1" to true, "fdff::1" to true, "[fd12:3456::1]" to true,
            "192.168.1.5." to true, "studio.local." to true, "localhost." to true, "8.8.8.8." to false,
        )

        for ((host, local) in table) {
            assertEquals(host, local, LocalHosts.isLocal(host))
            assertEquals(host, local, Hosts.isLocal(host))
        }
    }

    /**
     * NetworkPolicy's `normalize` and ProfileHosts' `canonicalHost` are two private copies of one rule, and
     * ProviderAllowlist relies on them giving the same host. Both are reached through their public behaviour: the host that
     * ProfileHosts gives must be on the allowlist that NetworkPolicy builds from the same text, and NetworkPolicy must name
     * it unchanged when it blocks it, so that the two forms are equal.
     */
    @Test
    fun theProfilesHostRuleAndThePolicysHostRuleGiveTheSameHost() {
        val hosts = listOf(
            "localhost", "LOCALHOST", "printer.local", "local", "example.local.net",
            "127.0.0.1", "127.255.255.254", "10.0.0.1", "10.255.0.1", "172.15.0.1", "172.16.0.1", "172.31.255.255", "172.32.0.1",
            "192.168.0.1", "192.169.0.1", "169.254.1.1", "169.255.1.1", "8.8.8.8", "256.0.0.1", "1.2.3", "1.2.3.4.5", "01.2.3.4",
            "::1", "[::1]", "fc00::1", "fd12:3456::1", "fe80::1", "fe90::1", "2001:db8::1", "[2001:db8::1]",
            "api.openai.com", "cli-chat-proxy.grok.com", "openrouter.ai",
            "API.OpenAI.COM", "Printer.Local", "b\u00FCcher.example", "\u4F8B\u3048.\u30C6\u30B9\u30C8", "[2001:DB8:0:0:0:0:0:1]",
            "FD12:3456:0:0:0:0:0:1", " api.openai.com ", "api.openai.com.", "studio.local.", "192.168.1.5.",
        )
        val junk = listOf("", "exa mple.com", "api.openai.com/v1", "user@api.openai.com", "a..b", "host:443", "[::1].")

        for (host in hosts) {
            val public = ProfileHosts.hosts(profile(discovered = listOf(host)), emptyCatalog)
            val bracketed = if (':' in host && !host.trim().startsWith("[")) "[${host.trim()}]" else host.trim()
            val local = ProfileHosts.localServers(profile(baseUrl = "http://$bracketed/"))
            assertEquals(host, 1, public.size + local.size)
            val policy = NetworkPolicy()
            if (public.isNotEmpty()) policy.setProfileHosts(listOf(host)) else policy.setLocalServers(listOf(host))
            val url = HttpUrl.Builder().scheme(if (public.isNotEmpty()) "https" else "http").host((public + local).single()).build()

            assertEquals(host, PolicyDecision.Allowed, policy.check(url))
            assertEquals(host, PolicyDecision.Blocked("${(public + local).single()} is not on the allowlist"), NetworkPolicy().check(url))
        }
        for (host in junk) {
            assertEquals(host, emptySet<String>(), ProfileHosts.hosts(profile(discovered = listOf(host)), emptyCatalog))
            assertThrows(host, IllegalArgumentException::class.java) { NetworkPolicy().setProfileHosts(listOf(host)) }
        }
    }

    private fun profile(baseUrl: String? = null, discovered: List<String> = emptyList()) = Profile(
        "test-1", "Test", "none", "none", AuthMethod.SERVER_ADDRESS, null,
        baseUrl = baseUrl, lightweight = ProfileModel("a"), versatile = ProfileModel("b"), discoveredHosts = discovered,
    )

    private val emptyCatalog = CatalogLoader.load(
        """{"schema": 1, "openclawCommit": "e6113549fe8800b4c736c5e8a131b47f2e015143", "providers": []}""",
        """{"schema": 1, "excludeProviders": [], "excludeAuthChoices": [], "choices": [],
            "providers": [], "localServers": [], "models": [], "freeModels": [],
            "unknownPrice": {"input": 5.0, "output": 25.0},
            "copilot": {"pricesCopiedOn": "2026-11-17", "pricesSource": "https://docs.github.com/", "models": [], "lightweight": [], "versatile": [],
                        "runtimeHeaders": {}, "ideHeaders": {}}}""",
    )
}
