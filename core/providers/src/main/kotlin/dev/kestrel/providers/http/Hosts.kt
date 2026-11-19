package dev.kestrel.providers.http

/**
 * Tells local hosts from public ones by the same string rules as `:core:privacy`'s LocalHosts, because this module must
 * not use InetAddress (design D1). `:app`'s HostsAgreementTest runs both over one table, so the two cannot drift.
 */
object Hosts {
    /** localhost, `*.local`, 127.0.0.0/8, ::1, the private IPv4 ranges, 169.254/16, fc00::/7 and fe80::. */
    fun isLocal(host: String): Boolean {
        val name = host.lowercase().removePrefix("[").removeSuffix("]")
        if (name == "localhost" || name.endsWith(".local")) return true
        if (':' in name) return name == "::1" || name.startsWith("fc") || name.startsWith("fd") || name.startsWith("fe80:")
        val octets = ipv4Octets(name) ?: return false
        val (first, second) = octets
        return first == 127 || first == 10 || (first == 172 && second in 16..31) ||
            (first == 192 && second == 168) || (first == 169 && second == 254)
    }

    /** localhost, 127.0.0.0/8 and ::1: the phone itself, for which Android 17 needs no permission (design D11). */
    fun isLoopback(host: String): Boolean {
        val name = host.lowercase().removePrefix("[").removeSuffix("]")
        if (name == "localhost" || name == "::1") return true
        return ipv4Octets(name)?.first() == 127
    }

    private fun ipv4Octets(name: String): List<Int>? {
        val parts = name.split('.')
        if (parts.size != 4) return null
        val octets = parts.map { it.toIntOrNull() ?: return null }
        return octets.takeIf { all -> all.all { it in 0..255 } }
    }
}
