package dev.kestrel.eval.egress

/**
 * The names of a capture's connections and DNS queries (design D11 of sub-project 6): a TLS ClientHello's server_name,
 * the questions of DNS queries, and DNS answers.
 */
object Names {
    /** The server_name of a TLS ClientHello at the start of [payload], in lower case, or null for anything else. */
    fun sni(payload: ByteArray): String? = try {
        if (payload.size < 43 || payload[0].toInt() != 22 || payload[5].toInt() != 1) {
            null
        } else {
            // The record's 5 bytes, the handshake's 4, the version's 2 and the random's 32.
            var at = 43
            at += 1 + (payload[at].toInt() and 0xff)
            at += 2 + PcapReader.u16(payload, at)
            at += 1 + (payload[at].toInt() and 0xff)
            val end = minOf(at + 2 + PcapReader.u16(payload, at), payload.size)
            at += 2
            var name: String? = null
            while (name == null && at + 4 <= end) {
                val type = PcapReader.u16(payload, at)
                val length = PcapReader.u16(payload, at + 2)
                // server_name: the list's length (2), the name's type (1), its length (2) and the name.
                if (type == 0) name = String(payload, at + 9, PcapReader.u16(payload, at + 7), Charsets.US_ASCII).lowercase()
                at += 4 + length
            }
            name
        }
    } catch (e: IndexOutOfBoundsException) {
        null
    }

    /** The names that the DNS query [payload] asks for, or null for an answer, a query without a question, or anything unreadable. */
    fun dnsQuestions(payload: ByteArray): List<String>? = try {
        val count = if (payload.size >= 12 && (payload[2].toInt() and 0x80) == 0) PcapReader.u16(payload, 4) else 0
        if (count == 0) {
            null
        } else {
            var at = 12
            List(count) {
                val (name, next) = name(payload, at)
                // The question's type and class.
                if (next + 4 > payload.size) throw IndexOutOfBoundsException("A DNS question is cut.")
                at = next + 4
                name
            }
        }
    } catch (e: IndexOutOfBoundsException) {
        null
    }

    /** Each A and AAAA address of the DNS answer [payload], with the name that its question asked for, through any CNAME. */
    fun dnsAnswers(payload: ByteArray): Map<String, String> = try {
        if (payload.size < 12 || (payload[2].toInt() and 0x80) == 0) {
            emptyMap()
        } else {
            var at = 12
            var asked: String? = null
            repeat(PcapReader.u16(payload, 4)) {
                val (name, next) = name(payload, at)
                if (asked == null) asked = name
                at = next + 4
            }
            val found = linkedMapOf<String, String>()
            repeat(PcapReader.u16(payload, 6)) {
                val next = name(payload, at).second
                val type = PcapReader.u16(payload, next)
                val length = PcapReader.u16(payload, next + 8)
                val data = next + 10
                val question = asked
                if (question != null && type == 1 && length == 4) found[PcapReader.ipv4(payload, data)] = question
                if (question != null && type == 28 && length == 16) found[PcapReader.ipv6(payload, data)] = question
                at = data + length
            }
            found
        }
    } catch (e: IndexOutOfBoundsException) {
        emptyMap()
    }

    /**
     * The DNS name at [start], its compression pointers followed, and the index after it. A label that holds a dot is
     * unreadable, because its name would read as another name, such as an allowed one.
     */
    private fun name(bytes: ByteArray, start: Int): Pair<String, Int> {
        val labels = mutableListOf<String>()
        var at = start
        var after = -1
        var jumps = 0
        while (true) {
            val length = bytes[at].toInt() and 0xff
            if (length == 0) {
                if (after < 0) after = at + 1
                break
            }
            if (length and 0xc0 == 0xc0) {
                if (after < 0) after = at + 2
                if (++jumps > 20) throw IndexOutOfBoundsException("A DNS name points in a circle.")
                at = ((length and 0x3f) shl 8) or (bytes[at + 1].toInt() and 0xff)
            } else {
                val label = String(bytes, at + 1, length, Charsets.US_ASCII)
                if ('.' in label) throw IndexOutOfBoundsException("A DNS label holds a dot.")
                labels.add(label)
                at += 1 + length
            }
        }
        return labels.joinToString(".").lowercase() to after
    }
}
