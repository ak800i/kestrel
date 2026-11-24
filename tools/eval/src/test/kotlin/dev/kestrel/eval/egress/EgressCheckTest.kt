package dev.kestrel.eval.egress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The capture's reader, its names and the egress check (design D11 of sub-project 6), on captures built byte by byte. */
class EgressCheckTest {
    private val kestrel = 10234

    @Test
    fun theReaderReadsBothIpVersionsTheTrailersUidAndDropsThePadding() {
        val packets = PcapReader.read(
            PcapBuilder().apply {
                tcp("10.215.173.1", 40000, "140.82.112.3", 443, byteArrayOf(1, 2, 3), kestrel, padding = 6)
                udp("fd00:0:0:0:0:0:0:1", 5353, "2606:4700:0:0:0:0:6810:84e5", 443, byteArrayOf(9), 1000)
            }.bytes(),
        )

        assertEquals(2, packets.size)
        val (tcp, udp) = packets
        assertEquals(listOf<Any?>(kestrel, PcapReader.TCP, "10.215.173.1", 40000, "140.82.112.3", 443, 43), listOf(tcp.uid, tcp.protocol, tcp.source, tcp.sourcePort, tcp.destination, tcp.destinationPort, tcp.size))
        assertEquals(listOf<Byte>(1, 2, 3), tcp.payload.toList())
        assertEquals(listOf<Any?>(1000, PcapReader.UDP, "fd00:0:0:0:0:0:0:1", "2606:4700:0:0:0:0:6810:84e5", 49), listOf(udp.uid, udp.protocol, udp.source, udp.destination, udp.size))
        assertEquals(listOf<Byte>(9), udp.payload.toList())
        assertThrows(IllegalArgumentException::class.java) { PcapReader.read(ByteArray(24)) }
    }

    @Test
    fun namesComeFromTheClientHelloOrFromTheDnsAnswersThroughTheirCname() {
        assertEquals("api.githubcopilot.com", Names.sni(PcapBuilder.clientHello("API.githubcopilot.com")))
        assertNull(Names.sni(byteArrayOf(22, 3, 1)))
        assertEquals(mapOf("140.82.112.3" to "github.com"), Names.dnsAnswers(PcapBuilder.dnsAnswer("github.com", "lb-140.github.com", "140.82.112.3")))
        assertEquals(
            mapOf("2606:4700:0:0:0:0:6810:84e5" to "huggingface.co"),
            Names.dnsAnswers(PcapBuilder.dnsAnswer("huggingface.co", null, "2606:4700:0:0:0:0:6810:84e5")),
        )
        assertEquals(emptyMap<String, String>(), Names.dnsAnswers(byteArrayOf(0, 1)))
    }

    @Test
    fun allowedNamesPassAndPcapdroidsDnsIsNoConnectionWhateverItsUid() {
        val packets = PcapReader.read(
            PcapBuilder().apply {
                udp("10.215.173.1", 33000, "10.215.173.2", 53, byteArrayOf(0), 1051)
                udp("10.215.173.2", 53, "10.215.173.1", 33000, PcapBuilder.dnsAnswer("cdn-lfs.hf.co", null, "18.66.1.1"), 1051)
                udp("fd00:2:fd00:1:fd00:1:fd00:1", 33001, "2620:fe:0:0:0:0:0:10", 53, byteArrayOf(0), 1051)
                udp("2620:fe:0:0:0:0:0:10", 53, "fd00:2:fd00:1:fd00:1:fd00:1", 33001, PcapBuilder.dnsAnswer("api.githubcopilot.com", null, "140.82.112.3"), 1051)
                tcp("10.215.173.1", 40001, "18.66.1.1", 443, byteArrayOf(0), kestrel)
                tcp("10.215.173.1", 40002, "140.82.112.3", 443, PcapBuilder.clientHello("api.githubcopilot.com"), kestrel)
                tcp("140.82.112.3", 443, "10.215.173.1", 40002, byteArrayOf(1, 1), kestrel)
            }.bytes(),
        )

        val result = EgressCheck.judge(packets, kestrel, listOf("api.githubcopilot.com"), listOf("*.hf.co", "huggingface.co"))

        assertTrue(result.failures.toString(), result.passed)
        assertEquals(listOf("cdn-lfs.hf.co", "api.githubcopilot.com"), result.connections.map { it.name })
        assertEquals(2, result.connections[1].packets)
        assertTrue(result.text().contains("host cdn-lfs.hf.co connections=1 bytes=41\n"))
        assertTrue(result.text().endsWith("result pass connections=2 failures=0\n"))
    }

    @Test
    fun aConnectionWithoutANameOrOutsideTheAllowlistAndAnotherUidFail() {
        val packets = PcapReader.read(
            PcapBuilder().apply {
                tcp("10.215.173.1", 40003, "93.184.216.34", 443, byteArrayOf(0), kestrel)
                tcp("10.215.173.1", 40004, "140.82.112.4", 443, PcapBuilder.clientHello("example.org"), kestrel)
                tcp("10.215.173.1", 40005, "140.82.112.5", 443, byteArrayOf(0), 1000)
                tcp("10.215.173.1", 40006, "140.82.112.6", 443, PcapBuilder.clientHello("hf.co"), kestrel)
                udp("fd00:2:fd00:1:fd00:1:fd00:1", 33002, "fd00:0:0:0:0:0:0:2", 53, byteArrayOf(0), 1051)
            }.bytes(),
        )

        val result = EgressCheck.judge(packets, kestrel, listOf("api.githubcopilot.com"), listOf("*.hf.co"))

        assertFalse(result.passed)
        assertEquals(
            listOf(
                "1 packet(s) of UID 1000, not Kestrel's", "1 packet(s) of UID 1051, not Kestrel's", "tcp 93.184.216.34 port 443 has no name",
                "example.org is not on the allowlist", "hf.co is not on the allowlist",
            ),
            result.failures,
        )
        assertTrue(EgressCheck.allowed("cdn-lfs.hf.co", listOf("*.hf.co")))
        assertFalse(EgressCheck.allowed("evilhf.co", listOf("*.hf.co")))
    }

    @Test
    fun recordsThatTheReaderCannotReadKeepTheirUidAndFailWhenTheyAreKestrelsOrOfNoUid() {
        val echo = byteArrayOf(8, 0, 0, 0, 0, 1, 0, 1)
        val capture = PcapBuilder().apply {
            tcp("10.215.173.1", 40002, "140.82.112.3", 443, PcapBuilder.clientHello("api.githubcopilot.com"), kestrel)
            raw("10.215.173.1", "93.184.216.34", PcapReader.ICMP, echo, kestrel)
            raw("fd00:0:0:0:0:0:0:1", "2001:db8:0:0:0:0:0:1", 58, echo, null)
            // A hop-by-hop extension header (next header 0) before a TCP header.
            raw("fd00:0:0:0:0:0:0:1", "2001:db8:0:0:0:0:0:1", 0, byteArrayOf(6, 0, 1, 4, 0, 0, 0, 0) + ByteArray(20), kestrel)
            raw("10.215.173.1", "93.184.216.34", PcapReader.TCP, ByteArray(10), kestrel)
            raw("10.215.173.1", "93.184.216.34", PcapReader.ICMP, echo, 1000)
        }.bytes()
        val allowlist = listOf("api.githubcopilot.com")

        val packets = PcapReader.read(capture)

        assertEquals(listOf(kestrel, kestrel, null, kestrel, kestrel, 1000), packets.map { it.uid })
        assertEquals(listOf(null, "icmp 10.215.173.1 > 93.184.216.34"), packets.take(2).map { it.problem })
        assertEquals(
            listOf(
                "1 packet(s) of UID 1000, not Kestrel's",
                "1 record(s) that the check cannot judge: icmp 10.215.173.1 > 93.184.216.34",
                "1 record(s) that the check cannot judge: icmpv6 fd00:0:0:0:0:0:0:1 > 2001:db8:0:0:0:0:0:1",
                "1 record(s) that the check cannot judge: ip protocol 0 fd00:0:0:0:0:0:0:1 > 2001:db8:0:0:0:0:0:1",
                "1 record(s) that the check cannot judge: a cut tcp header 10.215.173.1 > 93.184.216.34",
            ),
            EgressCheck.judge(packets, kestrel, allowlist, emptyList()).failures,
        )
        assertEquals(listOf("api.githubcopilot.com"), EgressCheck.judge(packets, kestrel, allowlist, emptyList()).connections.map { it.name })

        // A file cut inside its last record, or inside a record's header, fails.
        val cut = EgressCheck.judge(PcapReader.read(capture.copyOf(capture.size - 5)), kestrel, allowlist, emptyList())
        assertEquals("1 record(s) that the check cannot judge: a record that runs past the end of the file", cut.failures.last())
        assertFalse(cut.failures.any { "UID 1000" in it })
        val header = EgressCheck.judge(PcapReader.read(capture + ByteArray(7)), kestrel, allowlist, emptyList())
        assertEquals("1 record(s) that the check cannot judge: a record header that the end of the file cuts", header.failures.last())

        // A link type that the reader does not know keeps each frame's UID.
        val other = PcapReader.read(
            PcapBuilder(link = 228).apply {
                tcp("10.215.173.1", 40002, "140.82.112.3", 443, PcapBuilder.clientHello("api.githubcopilot.com"), kestrel)
                tcp("10.215.173.1", 40003, "140.82.112.3", 443, byteArrayOf(0), 1000)
            }.bytes(),
        )
        assertEquals(
            listOf("1 packet(s) of UID 1000, not Kestrel's", "1 record(s) that the check cannot judge: a frame of link type 228"),
            EgressCheck.judge(other, kestrel, allowlist, emptyList()).failures,
        )
    }

    @Test
    fun anAddressThatAnsweredForSeveralNamesPassesWithoutSniOnlyWhenEveryNameIsAllowed() {
        fun judge(first: String, second: String, sni: String?) = EgressCheck.judge(
            PcapReader.read(
                PcapBuilder().apply {
                    udp("10.215.173.2", 53, "10.215.173.1", 33000, PcapBuilder.dnsAnswer(first, null, "18.66.1.1"), 1051)
                    udp("10.215.173.2", 53, "10.215.173.1", 33001, PcapBuilder.dnsAnswer(second, null, "18.66.1.1"), 1051)
                    tcp("10.215.173.1", 40001, "18.66.1.1", 443, sni?.let { PcapBuilder.clientHello(it) } ?: byteArrayOf(0), kestrel)
                }.bytes(),
            ),
            kestrel, listOf("api.githubcopilot.com"), listOf("*.hf.co"),
        )

        for ((one, two) in listOf("cdn-lfs.hf.co" to "evil.example", "evil.example" to "cdn-lfs.hf.co")) {
            assertEquals(listOf("evil.example is not on the allowlist"), judge(one, two, null).failures)
            assertTrue(judge(one, two, "cdn-lfs.hf.co").passed)
            assertEquals(listOf("cas-bridge.hf.co"), judge(one.replace("evil.example", "cas-bridge.hf.co"), two.replace("evil.example", "cas-bridge.hf.co"), null).connections.map { it.name })
        }
    }

    @Test
    fun androidsPrivateDnsProbesToPcapdroidsDnsServersAreExemptOnlyWithoutPayload() {
        val vpn = "fd00:2:fd00:1:fd00:1:fd00:1"
        fun judge(build: PcapBuilder.() -> Unit) = EgressCheck.judge(PcapReader.read(PcapBuilder().apply(build).bytes()), kestrel, listOf("api.githubcopilot.com"), emptyList())
        fun PcapBuilder.probe(server: String, port: Int, local: String, localPort: Int, answer: ByteArray = ByteArray(0)) {
            tcp(local, localPort, server, port, ByteArray(0), -1)
            tcp(server, port, local, localPort, answer, -1)
        }

        val exempt = judge {
            probe(Pcapdroid.DNS_V6, 853, vpn, 38092)
            probe(Pcapdroid.DNS_V6, 853, vpn, 38108)
            probe(Pcapdroid.DNS_V4, 853, "10.215.173.1", 38200)
        }

        assertTrue(exempt.failures.toString(), exempt.passed)
        assertEquals(0, exempt.connections.size)
        val uidMinusOne = listOf("2 packet(s) of UID -1, not Kestrel's")
        assertEquals(uidMinusOne, judge { probe(Pcapdroid.DNS_V6, 853, vpn, 38092, answer = byteArrayOf(1)) }.failures)
        assertEquals(uidMinusOne, judge { probe(Pcapdroid.DNS_V6, 443, vpn, 38092) }.failures)
        assertEquals(uidMinusOne, judge { probe("2620:fe:0:0:0:0:0:9", 853, vpn, 38092) }.failures)
        assertEquals(listOf("tcp 2620:fe:0:0:0:0:0:10 port 853 has no name"), judge { tcp(vpn, 38300, Pcapdroid.DNS_V6, 853, byteArrayOf(1), kestrel) }.failures)
    }

    @Test(timeout = 10_000)
    fun theParsersFollowDnsPointersStopAtACycleAndReadTcpOptionsRawIpAndMalformedClientHellos() {
        assertEquals(mapOf("18.66.1.1" to "cdn-lfs.hf.co"), Names.dnsAnswers(PcapBuilder.dnsAnswer("cdn-lfs.hf.co", null, "18.66.1.1", compressed = true)))
        assertEquals(mapOf("140.82.112.3" to "github.com"), Names.dnsAnswers(PcapBuilder.dnsAnswer("github.com", "lb-140.github.com", "140.82.112.3", compressed = true)))
        val cycle = PcapBuilder.dnsAnswer("github.com", null, "140.82.112.3", compressed = true).also {
            val record = it.size - 16
            it[record] = 0xc0.toByte()
            it[record + 1] = record.toByte()
        }
        assertEquals(emptyMap<String, String>(), Names.dnsAnswers(cycle))

        val raw = PcapReader.read(PcapBuilder(link = 101).apply { tcp("10.215.173.1", 40000, "140.82.112.3", 443, byteArrayOf(7, 8), null, options = 3) }.bytes()).single()
        assertEquals(listOf<Any?>(null, null, 40000, 443, 54), listOf(raw.uid, raw.problem, raw.sourcePort, raw.destinationPort, raw.size))
        assertEquals(listOf<Byte>(7, 8), raw.payload.toList())

        val hello = PcapBuilder.clientHello("api.githubcopilot.com")
        assertNull(Names.sni(hello.copyOf(hello.size - 5)))
        assertNull(Names.sni(hello.copyOf().also { it[43] = 0xff.toByte() }))
        assertNull(Names.sni(hello.copyOf(50)))
    }
}
