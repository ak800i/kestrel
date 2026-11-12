package dev.kestrel.build.audit.fixtures

import java.net.Socket
import java.net.URL

// Compiled into the test classes, then read back as bytes by NetworkApiScannerTest.

class OpensUrl {
    fun open(url: URL) = url.openConnection()
}

class CreatesSocket {
    fun create() = Socket()
}

class MentionsSocketClass {
    fun type(): Class<*> = Socket::class.java
}

object SocketTools {
    fun close(socket: Socket) = socket.close()
}

class PassesSocket {
    fun finish(socket: Socket) = SocketTools.close(socket)
}

class Harmless {
    fun length(text: String) = text.length
}
