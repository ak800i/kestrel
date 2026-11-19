package dev.kestrel.privacy.network

import okhttp3.HttpUrl
import java.util.concurrent.atomic.AtomicBoolean

sealed interface PolicyDecision {
    data object Allowed : PolicyDecision

    data class Blocked(val reason: String) : PolicyDecision
}

/** The host allowlist (design spec, section 13.1). */
class NetworkPolicy {
    @Volatile
    private var profileHosts: Set<String> = emptySet()

    @Volatile
    private var localServers: Set<String> = emptySet()

    private val temporaryHosts = mutableMapOf<String, Int>()

    private val temporaryLocalServers = mutableMapOf<String, Int>()

    /** The hosts of the configured profiles, including the hosts they need for token refresh. */
    fun setProfileHosts(hosts: Collection<String>) {
        profileHosts = hosts.map(::normalize).toSet()
    }

    /** Local-network or localhost servers that the owner added. Only these may use plain HTTP. */
    fun setLocalServers(hosts: Collection<String>) {
        localServers = localOnly(hosts)
    }

    /** Allows [hosts] until the returned lease is closed, for example while a sign-in runs. */
    fun openTemporaryHosts(hosts: Collection<String>): AutoCloseable = lease(temporaryHosts, hosts.map(::normalize).toSet())

    /**
     * Allows the local [hosts], also over plain HTTP, until the returned lease is closed, for example while the owner checks
     * a local server before saving its profile (design D11 of sub-project 4). It refuses hosts that are not local, as
     * [setLocalServers] does.
     */
    fun openTemporaryLocalServers(hosts: Collection<String>): AutoCloseable = lease(temporaryLocalServers, localOnly(hosts))

    fun check(url: HttpUrl): PolicyDecision {
        val host = normalize(url.host)
        if (host in localServers || synchronized(temporaryLocalServers) { host in temporaryLocalServers }) return PolicyDecision.Allowed
        val temporary = synchronized(temporaryHosts) { host in temporaryHosts }
        // Membership first, so that the log says "not on the allowlist" for a host that is not, also over plain HTTP.
        if (host !in profileHosts && !temporary) return PolicyDecision.Blocked("$host is not on the allowlist")
        return if (url.isHttps) PolicyDecision.Allowed else PolicyDecision.Blocked("HTTPS is required for $host")
    }

    private fun localOnly(hosts: Collection<String>): Set<String> {
        val normalized = hosts.map(::normalize)
        normalized.firstOrNull { !LocalHosts.isLocal(it) }?.let {
            throw IllegalArgumentException("$it is not a local-network or localhost address")
        }
        return normalized.toSet()
    }

    /** Counts each host in [leases] until the returned lease is closed; closing it twice has no effect. */
    private fun lease(leases: MutableMap<String, Int>, hosts: Set<String>): AutoCloseable {
        synchronized(leases) {
            hosts.forEach { leases[it] = (leases[it] ?: 0) + 1 }
        }
        val closed = AtomicBoolean(false)
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                synchronized(leases) {
                    hosts.forEach { host ->
                        val remaining = leases.getValue(host) - 1
                        if (remaining == 0) leases.remove(host) else leases[host] = remaining
                    }
                }
            }
        }
    }

    /**
     * The host as OkHttp writes a URL's host (IDN in ASCII, IPv6 in its canonical form, lower case), without brackets or a
     * trailing dot. A text that OkHttp refuses as a host is refused, without the text, which could be anything.
     */
    private fun normalize(host: String): String {
        val name = host.trim().removePrefix("[").removeSuffix("]").removeSuffix(".")
        return try {
            HttpUrl.Builder().scheme("https").host(name).build().host
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("A configured host is not a valid host name")
        }
    }
}
