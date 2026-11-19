package dev.kestrel.privacy.network

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder

/**
 * The server of a browser sign-in's loopback redirect (design D10; RFC 8252): it lives in this package, the audit's only
 * exception, because it opens sockets. It binds only the addresses that the redirect names, never the wildcard address:
 * `127.0.0.1`, and `::1` too for a `localhost` redirect, because Chrome tries `::1` first. It fails closed: a port that is
 * taken on any address that it needs is reported as busy (null), so that a redirect never reaches another app's server;
 * it goes on with `127.0.0.1` alone only when the device has no IPv6 loopback. A null port means a free port.
 */
class LoopbackServer(private val ipv6Loopback: () -> Boolean = ::hasIpv6Loopback) {
    fun open(ipv6: Boolean, port: Int?, path: String): Session? {
        val both = ipv6 && ipv6Loopback()
        repeat(if (port == null) FREE_PORT_TRIES else 1) {
            val first = bind(IPV4, port ?: 0) ?: return null
            if (!both) return Session(listOf(first), path)
            val second = bind(IPV6, first.localPort)
            if (second != null) return Session(listOf(first, second), path)
            first.close()
            if (port != null) return null
        }
        return null
    }

    /**
     * One listening server. [await] blocks, so callers run it on Dispatchers.IO. It accepts only a `GET` of the exact path
     * and reads at most 8 KB of a request; other paths, such as Chrome's `/favicon.ico`, get 404; a request on the path with
     * a wrong or missing `state` gets a short error page, and the server keeps waiting; the callback with the right state
     * gets a short page without outside resources, and its query is returned. A field that the query names more than once
     * is left out of it, so that a sign-in never takes one of two values (a repeated `state` is therefore a missing one).
     * The server closes after that callback, after [await]'s time, or on [close].
     */
    class Session internal constructor(private val sockets: List<ServerSocket>, private val path: String) : AutoCloseable {
        val port: Int = sockets.first().localPort

        /** The addresses that the server listens on, for the device test that checks that it never binds the wildcard. */
        val addresses: List<InetAddress> = sockets.map { it.inetAddress }

        @Volatile
        private var closed = false

        fun await(expectedState: String, timeoutMillis: Long): Map<String, String>? {
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000
            try {
                sockets.forEach { it.soTimeout = ACCEPT_SLICE_MILLIS }
                while (!closed && System.nanoTime() < deadline) {
                    for (socket in sockets) {
                        val client = try {
                            socket.accept()
                        } catch (e: SocketTimeoutException) {
                            continue
                        } catch (e: IOException) {
                            if (closed) return null
                            // Such as no file descriptors left: wait a slice instead of trying again at once.
                            Thread.sleep(ACCEPT_SLICE_MILLIS.toLong())
                            continue
                        }
                        val query = try {
                            serve(client, expectedState)
                        } finally {
                            try {
                                client.close()
                            } catch (e: IOException) {
                                // A failed close does not end the session, also not after a good callback.
                            }
                        }
                        query?.let { return it }
                    }
                }
                return null
            } catch (e: IOException) {
                return null
            } finally {
                close()
            }
        }

        override fun close() {
            closed = true
            sockets.forEach {
                try {
                    it.close()
                } catch (e: IOException) {
                    // Already closed.
                }
            }
        }

        /** Answers one request, and returns the callback's query when it carries [expectedState]. */
        private fun serve(client: Socket, expectedState: String): Map<String, String>? {
            val line = try {
                readRequestLine(client)
            } catch (e: IOException) {
                null
            }
            val parts = line?.split(' ')
            if (parts == null || parts.size != 3 || parts[0] != "GET") {
                respond(client, 400, "Bad Request", PAGE_REFUSED)
                return null
            }
            val target = parts[1]
            if (target.substringBefore('?') != path) {
                respond(client, 404, "Not Found", PAGE_NOT_FOUND)
                return null
            }
            val query = parseQuery(target.substringAfter('?', ""))
            if (query["state"] != expectedState) {
                respond(client, 400, "Bad Request", PAGE_REFUSED)
                return null
            }
            respond(client, 200, "OK", PAGE_RECEIVED)
            return query
        }

        private fun readRequestLine(client: Socket): String? {
            val input = client.getInputStream()
            val head = ByteArrayOutputStream()
            // One limit for the whole head, not for each read, so that a client that sends a byte now and then cannot hold
            // the server past its time or Cancel.
            val until = System.nanoTime() + READ_TIMEOUT_MILLIS * 1_000_000L
            // The last four bytes that were read, so that the head's end is found without copying the head after each byte.
            var tail = 0
            while (head.size() < MAX_REQUEST_BYTES) {
                val left = (until - System.nanoTime()) / 1_000_000
                if (left <= 0 || closed) throw SocketTimeoutException()
                client.soTimeout = left.toInt()
                val byte = input.read()
                if (byte < 0) break
                head.write(byte)
                tail = (tail shl 8) or byte
                if (tail == END_OF_HEAD) break
            }
            return head.toByteArray().decodeToString().substringBefore("\r\n").takeIf { it.isNotEmpty() }
        }

        private fun parseQuery(query: String): Map<String, String> {
            val fields = linkedMapOf<String, String>()
            val repeated = mutableSetOf<String>()
            for (field in query.split('&')) {
                if (field.isEmpty()) continue
                val name = decode(field.substringBefore('=')) ?: continue
                val value = decode(field.substringAfter('=', "")) ?: continue
                if (fields.putIfAbsent(name, value) != null) repeated += name
            }
            return fields - repeated
        }

        private fun decode(text: String): String? =
            try {
                URLDecoder.decode(text, Charsets.UTF_8.name())
            } catch (e: IllegalArgumentException) {
                null
            }

        private fun respond(client: Socket, code: Int, reason: String, page: String) {
            val body = page.encodeToByteArray()
            val head = "HTTP/1.1 $code $reason\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
            try {
                client.getOutputStream().run {
                    write(head.encodeToByteArray())
                    write(body)
                    flush()
                }
            } catch (e: IOException) {
                // The browser went away; the server keeps waiting or returns the callback all the same.
            }
        }
    }

    companion object {
        private val IPV4: InetAddress = InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))
        private val IPV6: InetAddress = InetAddress.getByAddress("localhost", ByteArray(16).also { it[15] = 1 })
        private const val FREE_PORT_TRIES = 10
        private const val ACCEPT_SLICE_MILLIS = 200
        private const val READ_TIMEOUT_MILLIS = 2_000
        private const val MAX_REQUEST_BYTES = 8 * 1024
        /** `\r\n\r\n`, the end of a request's head, as four bytes in an Int. */
        private const val END_OF_HEAD = 0x0D0A0D0A
        private const val PAGE_RECEIVED =
            "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>Kestrel</title></head><body>" +
                "<p>Kestrel received the sign-in. You can close this tab.</p></body></html>"
        private const val PAGE_REFUSED =
            "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>Kestrel</title></head><body>" +
                "<p>This is not the sign-in that Kestrel is waiting for. Start the sign-in again in Kestrel.</p></body></html>"
        private const val PAGE_NOT_FOUND = "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>Kestrel</title></head><body></body></html>"

        private fun bind(address: InetAddress, port: Int): ServerSocket? {
            val socket = ServerSocket()
            return try {
                socket.bind(InetSocketAddress(address, port), BACKLOG)
                socket
            } catch (e: IOException) {
                socket.close()
                null
            }
        }

        private const val BACKLOG = 8

        /** Whether the device has an IPv6 loopback address that a server can bind. */
        fun hasIpv6Loopback(): Boolean = bind(IPV6, 0)?.let { it.close(); true } ?: false
    }
}
