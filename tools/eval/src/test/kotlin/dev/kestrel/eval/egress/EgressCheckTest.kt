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
                udp("fd00:0:0:0:0:0:0:1", 33001, "fd00:0:0:0:0:0:0:2", 53, byteArrayOf(0), 1051)
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
            }.bytes(),
        )

        val result = EgressCheck.judge(packets, kestrel, listOf("api.githubcopilot.com"), listOf("*.hf.co"))

        assertFalse(result.passed)
        assertEquals(
            listOf("1 packet(s) of UID 1000, not Kestrel's", "tcp 93.184.216.34 port 443 has no name", "example.org is not on the allowlist", "hf.co is not on the allowlist"),
            result.failures,
        )
        assertTrue(EgressCheck.allowed("cdn-lfs.hf.co", listOf("*.hf.co")))
        assertFalse(EgressCheck.allowed("evilhf.co", listOf("*.hf.co")))
    }
}
