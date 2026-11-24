package dev.kestrel.eval.egress

/** One connection of Kestrel's: its protocol, its remote address and port, its name, and its packets and IP bytes. */
data class Connection(val protocol: String, val remote: String, val port: Int, val name: String?, val packets: Int, val bytes: Long)

/** The egress check's result; [text] is `egress.txt`, which holds host names and counts only and stays out of git. */
data class EgressResult(val connections: List<Connection>, val failures: List<String>) {
    val passed: Boolean get() = failures.isEmpty()

    fun text(): String = buildString {
        for ((host, list) in connections.groupBy { it.name ?: it.remote }.toSortedMap()) append("host $host connections=${list.size} bytes=${list.sumOf { it.bytes }}\n")
        for (failure in failures) append("failure $failure\n")
        append("result ${if (passed) "pass" else "fail"} connections=${connections.size} failures=${failures.size}\n")
    }
}

/**
 * The egress check (design D11 of sub-project 6): a connection passes when its name, from its ClientHello or else from
 * the capture's DNS answers for its remote address, is on the device's allowlist or matches the model download's hosts
 * (`*.domain` covers every host below the domain); a connection without a name, one outside both, and any packet of
 * another UID than Kestrel's fail. DNS to and from PCAPdroid's own server on port 53 is exempt from the UID rule,
 * because Android's resolver may send an app's queries under its own UID, and is read only for its answers.
 */
object EgressCheck {
    fun judge(packets: List<Packet>, uid: Int, hosts: List<String>, modelHosts: List<String>): EgressResult {
        val dns = packets.filter { dnsOfPcapdroid(it) }.toHashSet()
        val names = mutableMapOf<String, String>()
        for (packet in dns) if (packet.sourcePort == 53) names.putAll(Names.dnsAnswers(packet.payload))
        val rest = packets.filter { it !in dns }
        val failures = mutableListOf<String>()
        for ((other, count) in rest.filter { it.uid != null && it.uid != uid }.groupingBy { it.uid }.eachCount()) failures.add("$count packet(s) of UID $other, not Kestrel's")
        val flows = LinkedHashMap<String, MutableList<Packet>>()
        for (packet in rest.filter { it.uid == null || it.uid == uid }) flows.getOrPut(key(packet)) { mutableListOf() }.add(packet)
        val connections = flows.values.map { flow ->
            val first = flow.first()
            val hello = flow.filter { it.protocol == PcapReader.TCP && it.source == first.source }.firstNotNullOfOrNull { Names.sni(it.payload) }
            Connection(if (first.protocol == PcapReader.TCP) "tcp" else "udp", first.destination, first.destinationPort, hello ?: names[first.destination], flow.size, flow.sumOf { it.size.toLong() })
        }
        for (connection in connections) {
            val name = connection.name
            when {
                name == null -> failures.add("${connection.protocol} ${connection.remote} port ${connection.port} has no name")
                !allowed(name, hosts + modelHosts) -> failures.add("$name is not on the allowlist")
            }
        }
        return EgressResult(connections, failures)
    }

    /** Whether [name] is one of [patterns], or lies below the domain of a pattern `*.domain`. */
    fun allowed(name: String, patterns: List<String>): Boolean =
        patterns.map { it.trim().lowercase() }.any { pattern -> if (pattern.startsWith("*.")) name.endsWith(pattern.substring(1)) else name == pattern }

    private fun dnsOfPcapdroid(packet: Packet): Boolean = packet.protocol == PcapReader.UDP &&
        ((packet.destinationPort == 53 && Pcapdroid.dnsServer(packet.destination)) || (packet.sourcePort == 53 && Pcapdroid.dnsServer(packet.source)))

    /** The connection of [packet]: its protocol and its two ends, whatever its direction. */
    private fun key(packet: Packet): String {
        val one = "${packet.source}|${packet.sourcePort}"
        val other = "${packet.destination}|${packet.destinationPort}"
        return "${packet.protocol}|${minOf(one, other)}|${maxOf(one, other)}"
    }
}
