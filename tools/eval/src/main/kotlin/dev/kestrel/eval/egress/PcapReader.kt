package dev.kestrel.eval.egress

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One IP packet of a capture: the UID of PCAPdroid's trailer, its addresses and ports, its protocol, its payload and its IP size. */
class Packet(
    val uid: Int?,
    val protocol: Int,
    val source: String,
    val sourcePort: Int,
    val destination: String,
    val destinationPort: Int,
    val payload: ByteArray,
    val size: Int,
)

/**
 * The reader of a classic pcap file (design D11 of sub-project 6), as PCAPdroid writes it with `dump_extensions`: each
 * frame's fake Ethernet header is dropped (or none for raw IP), PCAPdroid's trailer is found by its magic in the last 32
 * bytes and gives the UID, and the IP header's own length drops any padding. It reads IPv4 and IPv6 (without extension
 * headers) with TCP and UDP, and skips every other frame.
 */
object PcapReader {
    const val TCP = 6
    const val UDP = 17
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
        require(link == ETHERNET || link == RAW) { "The capture's link type $link is neither Ethernet nor raw IP." }
        val packets = mutableListOf<Packet>()
        var at = 24
        while (at + 16 <= bytes.size) {
            val length = header.getInt(at + 8)
            val start = at + 16
            if (length < 0 || start + length > bytes.size) break
            frame(bytes.copyOfRange(start, start + length), link)?.let { packets.add(it) }
            at = start + length
        }
        return packets
    }

    private fun frame(frame: ByteArray, link: Int): Packet? {
        var end = frame.size
        var uid: Int? = null
        if (end >= 32 && ByteBuffer.wrap(frame).getInt(end - 32) == TRAILER) {
            uid = ByteBuffer.wrap(frame).getInt(end - 28)
            end -= 32
        }
        val ip = if (link == ETHERNET) 14 else 0
        if (ip >= end) return null
        return when ((frame[ip].toInt() shr 4) and 0xf) {
            4 -> {
                if (ip + 20 > end) return null
                val total = u16(frame, ip + 2)
                transport(frame, frame[ip + 9].toInt() and 0xff, ipv4(frame, ip + 12), ipv4(frame, ip + 16), ip + (frame[ip].toInt() and 0xf) * 4, minOf(ip + total, end), uid, total)
            }
            6 -> {
                if (ip + 40 > end) return null
                val payload = u16(frame, ip + 4)
                transport(frame, frame[ip + 6].toInt() and 0xff, ipv6(frame, ip + 8), ipv6(frame, ip + 24), ip + 40, minOf(ip + 40 + payload, end), uid, 40 + payload)
            }
            else -> null
        }
    }

    private fun transport(frame: ByteArray, protocol: Int, source: String, destination: String, at: Int, last: Int, uid: Int?, size: Int): Packet? = when (protocol) {
        TCP -> if (at + 20 > last) null else {
            val data = minOf(at + ((frame[at + 12].toInt() shr 4) and 0xf) * 4, last)
            Packet(uid, TCP, source, u16(frame, at), destination, u16(frame, at + 2), frame.copyOfRange(data, last), size)
        }
        UDP -> if (at + 8 > last) null else Packet(uid, UDP, source, u16(frame, at), destination, u16(frame, at + 2), frame.copyOfRange(at + 8, last), size)
        else -> null
    }

    fun u16(bytes: ByteArray, at: Int): Int = ((bytes[at].toInt() and 0xff) shl 8) or (bytes[at + 1].toInt() and 0xff)

    fun ipv4(bytes: ByteArray, at: Int): String = (0 until 4).joinToString(".") { "${bytes[at + it].toInt() and 0xff}" }

    fun ipv6(bytes: ByteArray, at: Int): String = (0 until 8).joinToString(":") { Integer.toHexString(u16(bytes, at + 2 * it)) }
}
