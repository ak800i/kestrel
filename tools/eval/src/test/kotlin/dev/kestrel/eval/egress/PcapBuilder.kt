package dev.kestrel.eval.egress

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A classic pcap as PCAPdroid writes it with its extensions, built byte by byte for the tests: each frame with a fake
 * Ethernet header of 14 bytes, an IPv4 or IPv6 packet, any padding, and PCAPdroid's trailer of 32 bytes (its magic and
 * the UID, big-endian). An IPv6 address is written with its eight groups. With [link] 101 (raw IP), a frame has no
 * Ethernet header; a frame of no UID (null) has no trailer.
 */
class PcapBuilder(private val link: Int = 1) {
    private val out = ByteArrayOutputStream()

    init {
        listOf(le(0xa1b2c3d4L.toInt()), le16(2), le16(4), le(0), le(0), le(65535), le(link)).forEach { out.write(it) }
    }

    /** A TCP segment whose header has [options] words of NOP options after its 20 bytes. */
    fun tcp(source: String, sourcePort: Int, destination: String, destinationPort: Int, payload: ByteArray, uid: Int?, padding: Int = 0, options: Int = 0) {
        val header = ByteBuffer.allocate(20).putShort(sourcePort.toShort()).putShort(destinationPort.toShort()).putInt(1).putInt(0)
            .put(((5 + options) shl 4).toByte()).put(0x18.toByte()).putShort(65535.toShort()).putShort(0).putShort(0).array()
        frame(ip(source, destination, PcapReader.TCP, header + ByteArray(4 * options) { 1 } + payload), uid, padding)
    }

    fun udp(source: String, sourcePort: Int, destination: String, destinationPort: Int, payload: ByteArray, uid: Int?) {
        val header = ByteBuffer.allocate(8).putShort(sourcePort.toShort()).putShort(destinationPort.toShort()).putShort((8 + payload.size).toShort()).putShort(0).array()
        frame(ip(source, destination, PcapReader.UDP, header + payload), uid, 0)
    }

    /** An IP packet of [protocol] (or, for IPv6, of the next header [protocol]) that carries [transport] as it is. */
    fun raw(source: String, destination: String, protocol: Int, transport: ByteArray, uid: Int?) {
        frame(ip(source, destination, protocol, transport), uid, 0)
    }

    fun bytes(): ByteArray = out.toByteArray()

    private fun ip(source: String, destination: String, protocol: Int, transport: ByteArray): ByteArray = if (':' in source) {
        ByteBuffer.allocate(40 + transport.size).put(0x60.toByte()).put(0).putShort(0).putShort(transport.size.toShort()).put(protocol.toByte()).put(64)
            .put(v6(source)).put(v6(destination)).put(transport).array()
    } else {
        ByteBuffer.allocate(20 + transport.size).put(0x45.toByte()).put(0).putShort((20 + transport.size).toShort()).putInt(0).put(64).put(protocol.toByte())
            .putShort(0).put(v4(source)).put(v4(destination)).put(transport).array()
    }

    private fun frame(ip: ByteArray, uid: Int?, padding: Int) {
        val trailer = uid?.let { ByteBuffer.allocate(32).putInt(0x01072021).putInt(it).array() } ?: ByteArray(0)
        val frame = ByteArray(if (link == 101) 0 else 14) + ip + ByteArray(padding) + trailer
        listOf(le(1_795_000_000), le(0), le(frame.size), le(frame.size), frame).forEach { out.write(it) }
    }

    companion object {
        private fun le(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

        private fun le16(value: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()

        private fun v4(address: String) = address.split('.').map { it.toInt().toByte() }.toByteArray()

        private fun v6(address: String) = ByteBuffer.allocate(16).apply { address.split(':').forEach { putShort(it.toInt(16).toShort()) } }.array()

        private fun u16(value: Int) = byteArrayOf((value shr 8).toByte(), value.toByte())

        private fun u24(value: Int) = byteArrayOf((value shr 16).toByte(), (value shr 8).toByte(), value.toByte())

        private fun pointer(at: Int) = byteArrayOf((0xc0 or (at shr 8)).toByte(), at.toByte())

        private fun dnsName(name: String) = name.split('.').flatMap { listOf(it.length.toByte()) + it.toByteArray().toList() }.toByteArray() + byteArrayOf(0)

        /** A TLS 1.3 ClientHello whose only extension is server_name [name]. */
        fun clientHello(name: String): ByteArray {
            val host = name.toByteArray()
            val serverName = u16(1 + 2 + host.size) + byteArrayOf(0) + u16(host.size) + host
            val extensions = u16(0) + u16(serverName.size) + serverName
            val body = u16(0x0303) + ByteArray(32) + byteArrayOf(0) + u16(2) + u16(0x1301) + byteArrayOf(1, 0) + u16(extensions.size) + extensions
            val handshake = byteArrayOf(1) + u24(body.size) + body
            return byteArrayOf(22) + u16(0x0301) + u16(handshake.size) + handshake
        }

        /** A DNS query, as a stub resolver sends it: one question for [name], of type A, with recursion desired. */
        fun dnsQuery(name: String): ByteArray = u16(0x4321) + u16(0x0100) + u16(1) + u16(0) + u16(0) + u16(0) + dnsName(name) + u16(1) + u16(1)

        /**
         * A DNS answer to [asked], through the CNAME [alias] when given, with the A or AAAA record [address]. When
         * [compressed], as real answers are, each record names its owner with a pointer: to the question, or to the alias.
         */
        fun dnsAnswer(asked: String, alias: String?, address: String, compressed: Boolean = false): ByteArray {
            val type = if (':' in address) 28 else 1
            val data = if (type == 28) v6(address) else v4(address)
            val question = dnsName(asked) + u16(type) + u16(1)
            val owner = if (compressed) pointer(12) else dnsName(asked)
            val cname = alias?.let { owner + u16(5) + u16(1) + ByteArray(4) + u16(dnsName(it).size) + dnsName(it) } ?: ByteArray(0)
            val aliasAt = 12 + question.size + owner.size + 10
            val recordOwner = when {
                !compressed -> dnsName(alias ?: asked)
                alias != null -> pointer(aliasAt)
                else -> pointer(12)
            }
            val record = recordOwner + u16(type) + u16(1) + byteArrayOf(0, 0, 0, 60) + u16(data.size) + data
            val answers = if (alias == null) 1 else 2
            return u16(0x1234) + u16(0x8180) + u16(1) + u16(answers) + u16(0) + u16(0) + question + cname + record
        }
    }
}
