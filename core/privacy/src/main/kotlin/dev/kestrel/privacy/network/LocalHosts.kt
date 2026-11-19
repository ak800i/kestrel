package dev.kestrel.privacy.network

/** Recognises localhost and private-network addresses, the only hosts that may use plain HTTP (design spec, section 13.1). */
object LocalHosts {
    fun isLocal(host: String): Boolean {
        val name = host.lowercase().removePrefix("[").removeSuffix("]").removeSuffix(".")
        if (name == "localhost" || name.endsWith(".local")) return true
        if (':' in name) return name == "::1" || UNIQUE_LOCAL.containsMatchIn(name) || name.startsWith("fe80:")
        val octets = ipv4Octets(name) ?: return false
        val (first, second) = octets
        return first == 127 || first == 10 || (first == 172 && second in 16..31) ||
            (first == 192 && second == 168) || (first == 169 && second == 254)
    }

    private fun ipv4Octets(name: String): List<Int>? {
        val parts = name.split('.')
        if (parts.size != 4) return null
        val octets = parts.map { if (OCTET.matches(it)) it.toInt() else return null }
        return octets.takeIf { all -> all.all { it in 0..255 } }
    }

    // fc00::/7: the first group has all four hex digits, so "fc::1" (0x00fc) is not in it.
    private val UNIQUE_LOCAL = Regex("^f[cd][0-9a-f]{2}:")

    // ASCII digits without a sign or a leading zero, so that "0127", "+10" and other scripts' digits are not octets.
    private val OCTET = Regex("0|[1-9][0-9]{0,2}")
}
