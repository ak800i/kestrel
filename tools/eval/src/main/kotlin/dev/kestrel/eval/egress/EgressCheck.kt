package dev.kestrel.eval.egress

/** One connection of Kestrel's: its protocol, its remote address and port, its name, and its packets and IP bytes. */
data class Connection(val protocol: String, val remote: String, val port: Int, val name: String?, val packets: Int, val bytes: Long)

/**
 * The egress check's result: Kestrel's connections, the failures, and the names that Kestrel's own DNS queries asked for,
 * each with its number of queries ([queries]). [text] is `egress.txt`, which holds host names and counts only and stays
 * out of git.
 */
data class EgressResult(val connections: List<Connection>, val failures: List<String>, val queries: Map<String, Int> = emptyMap()) {
    val passed: Boolean get() = failures.isEmpty()

    fun text(): String = buildString {
        for ((host, list) in connections.groupBy { it.name ?: it.remote }.toSortedMap()) append("host $host connections=${list.size} bytes=${list.sumOf { it.bytes }}\n")
        for ((name, count) in queries.toSortedMap()) append("dns $name queries=$count\n")
        for (failure in failures) append("failure $failure\n")
        append("result ${if (passed) "pass" else "fail"} connections=${connections.size} failures=${failures.size}\n")
    }
}

/**
 * The egress check (design D11 of sub-project 6): a connection passes when its name is on the device's allowlist or
 * matches the model download's hosts (`*.domain` covers every host below the domain). Its name is its ClientHello's
 * server_name; without one, the capture's DNS answers for its remote address name it, and it passes only when every name
 * that answered for that address is allowed. A connection without a name, one outside both lists, any packet of another
 * UID than Kestrel's, and any record of Kestrel's or of no UID that the reader could not read (ICMP among them) fail.
 * Two kinds of traffic to and from PCAPdroid's DNS servers are exempt from these rules, whatever their UID, because
 * Android's resolver sends them under its own UID: UDP on port 53, which is read for its answers, and a TCP connection
 * on port 853 without a byte of payload in either direction, which is Android's probe for DNS over TLS that PCAPdroid's
 * `auto_block_private_dns` resets. A port-853 connection that carries any payload is judged like any other. A DNS query
 * of Kestrel's UID or of none, which Kestrel's own code sent past the resolver, is judged by the names that it asks for,
 * because a name carries data to its domain's name server even when no connection follows: each name must be allowed,
 * and a query that the check cannot read fails. The resolver's own queries, under its UID, stay exempt.
 */
object EgressCheck {
    fun judge(packets: List<Packet>, uid: Int, hosts: List<String>, modelHosts: List<String>): EgressResult {
        val patterns = hosts + modelHosts
        val readable = packets.filter { it.problem == null }
        val dns = readable.filter { dnsOfPcapdroid(it) }
        val names = HashMap<String, java.util.SortedSet<String>>()
        for (packet in dns) if (packet.sourcePort == 53) for ((address, name) in Names.dnsAnswers(packet.payload)) names.getOrPut(address) { sortedSetOf() }.add(name)
        val probes = readable.filter { privateDnsProbe(it) }.groupBy { key(it) }.values.filter { flow -> flow.all { it.payload.isEmpty() } }.flatten()
        val queries = dns.filter { it.destinationPort == 53 && Pcapdroid.dnsServer(it.destination) && (it.uid == null || it.uid == uid) }
        val asked = sortedMapOf<String, Int>()
        var unread = 0
        for (query in queries) {
            val names = Names.dnsQuestions(query.payload)
            if (names == null) unread++ else names.forEach { asked.merge(it, 1, Int::plus) }
        }
        val exempt = (dns + probes).toHashSet()
        val rest = packets.filter { it !in exempt }
        val failures = mutableListOf<String>()
        for ((other, count) in rest.filter { it.uid != null && it.uid != uid }.groupingBy { it.uid }.eachCount()) failures.add("$count packet(s) of UID $other, not Kestrel's")
        val kestrels = rest.filter { it.uid == null || it.uid == uid }
        for ((problem, count) in kestrels.filter { it.problem != null }.groupingBy { it.problem }.eachCount()) failures.add("$count record(s) that the check cannot judge: $problem")
        val flows = LinkedHashMap<String, MutableList<Packet>>()
        for (packet in kestrels.filter { it.problem == null }) flows.getOrPut(key(packet)) { mutableListOf() }.add(packet)
        val connections = flows.values.map { flow ->
            val first = flow.first()
            val hello = flow.filter { it.protocol == PcapReader.TCP && it.source == first.source }.firstNotNullOfOrNull { Names.sni(it.payload) }
            val answered = names[first.destination].orEmpty()
            val name = hello ?: answered.firstOrNull { !allowed(it, patterns) } ?: answered.firstOrNull()
            Connection(if (first.protocol == PcapReader.TCP) "tcp" else "udp", first.destination, first.destinationPort, name, flow.size, flow.sumOf { it.size.toLong() })
        }
        for (connection in connections) {
            val name = connection.name
            when {
                name == null -> failures.add("${connection.protocol} ${connection.remote} port ${connection.port} has no name")
                !allowed(name, patterns) -> failures.add("$name is not on the allowlist")
            }
        }
        for (name in asked.keys.filter { !allowed(it, patterns) }) failures.add("a DNS query of Kestrel's for $name, which is not on the allowlist")
        if (unread > 0) failures.add("$unread DNS ${if (unread == 1) "query" else "queries"} of Kestrel's that the check cannot read")
        return EgressResult(connections, failures, asked)
    }

    /** Whether [name] is one of [patterns], or lies below the domain of a pattern `*.domain`. */
    fun allowed(name: String, patterns: List<String>): Boolean =
        patterns.map { it.trim().lowercase() }.any { pattern -> if (pattern.startsWith("*.")) name.endsWith(pattern.substring(1)) else name == pattern }

    private fun dnsOfPcapdroid(packet: Packet): Boolean = packet.protocol == PcapReader.UDP &&
        ((packet.destinationPort == 53 && Pcapdroid.dnsServer(packet.destination)) || (packet.sourcePort == 53 && Pcapdroid.dnsServer(packet.source)))

    private fun privateDnsProbe(packet: Packet): Boolean = packet.protocol == PcapReader.TCP &&
        ((packet.destinationPort == 853 && Pcapdroid.dnsServer(packet.destination)) || (packet.sourcePort == 853 && Pcapdroid.dnsServer(packet.source)))

    /** The connection of [packet]: its protocol and its two ends, whatever its direction. */
    private fun key(packet: Packet): String {
        val one = "${packet.source}|${packet.sourcePort}"
        val other = "${packet.destination}|${packet.destinationPort}"
        return "${packet.protocol}|${minOf(one, other)}|${maxOf(one, other)}"
    }
}
