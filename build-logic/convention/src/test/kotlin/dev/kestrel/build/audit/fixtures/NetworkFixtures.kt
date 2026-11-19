package dev.kestrel.build.audit.fixtures

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.http.HttpClient
import java.nio.channels.AsynchronousServerSocketChannel

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

class ReadsUrlText {
    fun read(url: URL) = url.readText()
}

class ReadsNetworkField {
    fun version() = HttpClient.Version.HTTP_2
}

class ResolvesNames {
    fun byName() = InetAddress.getByName("host.test")

    fun allByName() = InetAddress.getAllByName("host.test")

    fun localHost() = InetAddress.getLocalHost()

    fun reachable(address: InetAddress) = address.isReachable(1)

    fun name(address: InetAddress) = address.hostName

    fun canonicalName(address: InetAddress) = address.canonicalHostName
}

class ResolvesNamesOnSubtypes {
    fun byName4() = Inet4Address.getByName("host.test")

    fun allByName6() = Inet6Address.getAllByName("host.test")

    fun localHost4() = Inet4Address.getLocalHost()

    fun reachable4(address: Inet4Address) = address.isReachable(1)

    fun name6(address: Inet6Address) = address.hostName

    fun canonicalName4(address: Inet4Address) = address.canonicalHostName
}

class ReadsSocketAddressNames {
    fun name(address: InetSocketAddress) = address.hostName

    fun nameWithoutLookup(address: InetSocketAddress) = address.hostString
}

class BuildsSocketAddresses {
    fun resolved() = InetSocketAddress("host.test", 443)

    fun unresolved() = InetSocketAddress.createUnresolved("host.test", 443)

    fun fromAddress(address: InetAddress) = InetSocketAddress(address, 443)
}

class OpensAsynchronousServer {
    fun open() = AsynchronousServerSocketChannel.open()
}
