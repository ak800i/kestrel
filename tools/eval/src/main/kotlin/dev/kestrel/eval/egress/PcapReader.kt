package dev.kestrel.eval.egress

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One record of a capture: the UID of PCAPdroid's trailer, its addresses and ports, its protocol, its payload and its IP
 * size. [problem] is null for a TCP or UDP packet that the reader could read; otherwise it says what the record is, and
 * the record keeps the UID of its trailer when it has one, its addresses and IP protocol when its IP header could be
 * read, ports 0 and no payload.
 */
class Packet(
    val uid: Int?,
    val protocol: Int,
    val source: String,
    val sourcePort: Int,
    val destination: String,
    val destinationPort: Int,
    val payload: ByteArray,
    val size: Int,
    val problem: String? = null,
)

/**
 * The reader of a classic pcap file (design D11 of sub-project 6), as PCAPdroid writes it with `dump_extensions`: each
 * frame's fake Ethernet header is dropped (or none for raw IP), PCAPdroid's trailer is found by its magic in the last 32
 * bytes and gives the UID, and the IP header's own length drops any padding. It reads IPv4 and IPv6 (without extension
 * headers) with TCP and UDP. It drops no record: every other one (another IP protocol such as ICMP, an IPv6 extension
 * header, a cut or malformed header, a frame of an unknown link type, a record cut by the end of the file) becomes a
 * [Packet] with its [Packet.problem], so that the egress check fails closed on it.
 */
object PcapReader {
    const val ICMP = 1
    const val TCP = 6
    const val UDP = 17
    const val ICMPV6 = 58

    /** The [Packet.protocol] of a record whose IP header could not be read. */
    const val NOT_IP = -1
    private const val TRAILER = 0x01072021
    private const val ETHERNET = 1
    private const val RAW = 101

    fun read(bytes: ByteArray): List<Packet> {
        require(bytes.size >= 24) { "The capture has no pcap header." }
        val order = when (ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.BIG_ENDIAN).int) {
            0xa1b2c3d4.toInt(), 0xa1b23c4d.toInt() -> ByteOrder.BIG_ENDIAN
            0xd4c3b2a1.toInt(), 0x4d3cb2a1.toInt() -> ByteOrder.LITTLE_ENDIAN
            else -> throw IllegalArgumentException("The capture is not a classic pcap file.")
        }
        val header = ByteBuffer.wrap(bytes).order(order)
        val link = header.getInt(20)
        val packets = mutableListOf<Packet>()
        var at = 24
        while (at < bytes.size) {
            if (at + 16 > bytes.size) {
                packets.add(unreadable(null, bytes.size - at, "a record header that the end of the file cuts"))
                break
            }
            val length = header.getInt(at + 8)
            val start = at + 16
            if (length < 0 || length > bytes.size - start) {
                packets.add(unreadable(null, bytes.size - start, "a record that runs past the end of the file"))
                break
            }
            packets.add(frame(bytes.copyOfRange(start, start + length), link))
            at = start + length
        }
        return packets
    }

    private fun frame(frame: ByteArray, link: Int): Packet {
        var end = frame.size
        var uid: Int? = null
        if (end >= 32 && ByteBuffer.wrap(frame).getInt(end - 32) == TRAILER) {
            uid = ByteBuffer.wrap(frame).getInt(end - 28)
            end -= 32
        }
        if (link != ETHERNET && link != RAW) return unreadable(uid, end, "a frame of link type $link")
        val ip = if (link == ETHERNET) 14 else 0
        if (ip >= end) return unreadable(uid, end, "a frame without an ip packet")
        return when ((frame[ip].toInt() shr 4) and 0xf) {
            4 -> {
                if (ip + 20 > end) return unreadable(uid, end - ip, "a cut ipv4 header")
                val total = u16(frame, ip + 2)
                val headerLength = (frame[ip].toInt() and 0xf) * 4
                val protocol = frame[ip + 9].toInt() and 0xff
                val source = ipv4(frame, ip + 12)
                val destination = ipv4(frame, ip + 16)
                if (headerLength < 20 || total < headerLength || ip + headerLength > end) {
                    Packet(uid, protocol, source, 0, destination, 0, ByteArray(0), total, "a malformed ipv4 header $source > $destination")
                } else {
                    transport(frame, protocol, source, destination, ip + headerLength, minOf(ip + total, end), uid, total)
                }
            }
            6 -> {
                if (ip + 40 > end) return unreadable(uid, end - ip, "a cut ipv6 header")
                val payload = u16(frame, ip + 4)
                transport(frame, frame[ip + 6].toInt() and 0xff, ipv6(frame, ip + 8), ipv6(frame, ip + 24), ip + 40, minOf(ip + 40 + payload, end), uid, 40 + payload)
            }
            else -> unreadable(uid, end - ip, "a frame that is neither ipv4 nor ipv6")
        }
    }

    private fun transport(frame: ByteArray, protocol: Int, source: String, destination: String, at: Int, last: Int, uid: Int?, size: Int): Packet {
        val ends = "$source > $destination"
        fun unread(what: String) = Packet(uid, protocol, source, 0, destination, 0, ByteArray(0), size, "$what $ends")
        return when (protocol) {
            TCP -> {
                if (at + 20 > last) return unread("a cut tcp header")
                val data = at + ((frame[at + 12].toInt() shr 4) and 0xf) * 4
                if (data < at + 20 || data > last) {
                    unread("a malformed tcp header")
                } else {
                    Packet(uid, TCP, source, u16(frame, at), destination, u16(frame, at + 2), frame.copyOfRange(data, last), size)
                }
            }
            UDP -> if (at + 8 > last) unread("a cut udp header") else Packet(uid, UDP, source, u16(frame, at), destination, u16(frame, at + 2), frame.copyOfRange(at + 8, last), size)
            ICMP -> unread("icmp")
            ICMPV6 -> unread("icmpv6")
            else -> unread("ip protocol $protocol")
        }
    }

    private fun unreadable(uid: Int?, size: Int, problem: String) = Packet(uid, NOT_IP, "", 0, "", 0, ByteArray(0), maxOf(size, 0), problem)

    fun u16(bytes: ByteArray, at: Int): Int = ((bytes[at].toInt() and 0xff) shl 8) or (bytes[at + 1].toInt() and 0xff)

    fun ipv4(bytes: ByteArray, at: Int): String = (0 until 4).joinToString(".") { "${bytes[at + it].toInt() and 0xff}" }

    fun ipv6(bytes: ByteArray, at: Int): String = (0 until 8).joinToString(":") { Integer.toHexString(u16(bytes, at + 2 * it)) }
}
