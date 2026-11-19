package dev.kestrel.build.audit

import dev.kestrel.build.audit.fixtures.BuildsSocketAddresses
import dev.kestrel.build.audit.fixtures.CreatesSocket
import dev.kestrel.build.audit.fixtures.Harmless
import dev.kestrel.build.audit.fixtures.JavaMethodReference
import dev.kestrel.build.audit.fixtures.MentionsSocketClass
import dev.kestrel.build.audit.fixtures.OpensAsynchronousServer
import dev.kestrel.build.audit.fixtures.OpensUrl
import dev.kestrel.build.audit.fixtures.PassesSocket
import dev.kestrel.build.audit.fixtures.ReadsNetworkField
import dev.kestrel.build.audit.fixtures.ReadsSocketAddressNames
import dev.kestrel.build.audit.fixtures.ReadsUrlText
import dev.kestrel.build.audit.fixtures.ResolvesNames
import dev.kestrel.build.audit.fixtures.ResolvesNamesOnSubtypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkApiScannerTest {
    private val scanner = NetworkApiScanner()

    private fun referencesIn(type: Class<*>): List<String> {
        val bytes = type.getResourceAsStream("/" + type.name.replace('.', '/') + ".class")!!.use { it.readBytes() }
        return scanner.scan(bytes).map { it.reference }
    }

    @Test
    fun findsUrlOpenConnection() {
        assertEquals(listOf("java/net/URL.openConnection"), referencesIn(OpensUrl::class.java))
    }

    @Test
    fun findsSocketConstruction() {
        assertTrue(referencesIn(CreatesSocket::class.java).contains("java/net/Socket"))
    }

    @Test
    fun findsClassLiteral() {
        assertEquals(listOf("java/net/Socket"), referencesIn(MentionsSocketClass::class.java))
    }

    @Test
    fun findsCallsWhoseSignatureMentionsANetworkType() {
        assertEquals(
            listOf("dev/kestrel/build/audit/fixtures/SocketTools.close"),
            referencesIn(PassesSocket::class.java),
        )
    }

    @Test
    fun findsJavaMethodReference() {
        assertEquals(listOf("java/net/URL.openStream"), referencesIn(JavaMethodReference::class.java))
    }

    @Test
    fun ignoresOrdinaryCode() {
        assertEquals(emptyList<String>(), referencesIn(Harmless::class.java))
    }

    @Test
    fun findsKotlinsUrlReadingFunctions() {
        assertEquals(listOf("kotlin/io/TextStreamsKt.readBytes"), referencesIn(ReadsUrlText::class.java))
    }

    @Test
    fun findsFieldsOfNetworkTypes() {
        assertEquals(listOf("java/net/http/HttpClient\$Version.HTTP_2"), referencesIn(ReadsNetworkField::class.java))
    }

    @Test
    fun findsInetAddressMethodsThatLookUpOrReachAHost() {
        assertEquals(
            listOf(
                "java/net/InetAddress.getByName", "java/net/InetAddress.getAllByName", "java/net/InetAddress.getLocalHost",
                "java/net/InetAddress.isReachable", "java/net/InetAddress.getHostName", "java/net/InetAddress.getCanonicalHostName",
            ),
            referencesIn(ResolvesNames::class.java),
        )
    }

    @Test
    fun findsTheSameLookupsThroughInet4AddressAndInet6Address() {
        assertEquals(
            listOf(
                "java/net/Inet4Address.getByName", "java/net/Inet6Address.getAllByName", "java/net/Inet4Address.getLocalHost",
                "java/net/Inet4Address.isReachable", "java/net/Inet6Address.getHostName", "java/net/Inet4Address.getCanonicalHostName",
            ),
            referencesIn(ResolvesNamesOnSubtypes::class.java),
        )
    }

    @Test
    fun findsTheReverseLookupOfASocketAddressButNotItsHostString() {
        assertEquals(listOf("java/net/InetSocketAddress.getHostName"), referencesIn(ReadsSocketAddressNames::class.java))
    }

    @Test
    fun findsOnlyTheSocketAddressConstructorThatLooksUpAName() {
        assertEquals(listOf("java/net/InetSocketAddress.<init>"), referencesIn(BuildsSocketAddresses::class.java))
    }

    @Test
    fun findsAsynchronousServerSockets() {
        assertEquals(
            listOf("java/nio/channels/AsynchronousServerSocketChannel.open"),
            referencesIn(OpensAsynchronousServer::class.java),
        )
    }

    @Test
    fun classifiesAndroidNetworkMethods() {
        assertTrue(NetworkApiRules.isNetworkCall("android/net/Network", "getByName", "(Ljava/lang/String;)Ljava/net/InetAddress;"))
        assertTrue(NetworkApiRules.isNetworkCall("android/net/Network", "getAllByName", "(Ljava/lang/String;)[Ljava/net/InetAddress;"))
        assertTrue(NetworkApiRules.isNetworkCall("android/net/Network", "bindSocket", "(Ljava/io/FileDescriptor;)V"))
        assertTrue(NetworkApiRules.isNetworkCall("android/net/Network", "openConnection", "(Ljava/net/URL;)Ljava/net/URLConnection;"))
        assertTrue(NetworkApiRules.isNetworkCall("android/net/Network", "getSocketFactory", "()Ljavax/net/SocketFactory;"))
        assertFalse(NetworkApiRules.isNetworkCall("android/net/Network", "getNetworkHandle", "()J"))
    }

    @Test
    fun classifiesTheUrlMethodsThatResolveItsHost() {
        assertTrue(NetworkApiRules.isNetworkCall("java/net/URL", "equals", "(Ljava/lang/Object;)Z"))
        assertTrue(NetworkApiRules.isNetworkCall("java/net/URL", "hashCode", "()I"))
        assertTrue(NetworkApiRules.isNetworkCall("java/net/URL", "sameFile", "(Ljava/net/URL;)Z"))
        assertFalse(NetworkApiRules.isNetworkCall("java/net/URL", "getHost", "()Ljava/lang/String;"))
    }

    @Test
    fun classifiesOkHttpsNameLookups() {
        assertTrue(NetworkApiRules.isNetworkType("okhttp3/Dns"))
        assertTrue(NetworkApiRules.isNetworkType("okhttp3/Dns\$Companion"))
        assertTrue(NetworkApiRules.isNetworkType("okhttp3/android/AndroidAsyncDns"))
        assertTrue(NetworkApiRules.isNetworkType("okhttp3/dnsoverhttps/DnsOverHttps"))
        assertTrue(NetworkApiRules.isNetworkCall("okhttp3/Dns", "lookup", "(Ljava/lang/String;)Ljava/util/List;"))
        assertFalse(NetworkApiRules.isNetworkType("okhttp3/HttpUrl"))
    }

    @Test
    fun classifiesAndroidsDnsResolver() {
        assertTrue(NetworkApiRules.isNetworkType("android/net/DnsResolver"))
        assertTrue(NetworkApiRules.isNetworkType("android/net/DnsResolver\$Callback"))
        assertTrue(NetworkApiRules.isNetworkCall("android/net/DnsResolver", "getInstance", "()Landroid/net/DnsResolver;"))
        assertTrue(
            NetworkApiRules.isNetworkCall(
                "android/net/DnsResolver", "query",
                "(Landroid/net/Network;Ljava/lang/String;ILjava/util/concurrent/Executor;Landroid/os/CancellationSignal;" +
                    "Landroid/net/DnsResolver\$Callback;)V",
            ),
        )
        assertFalse(NetworkApiRules.isNetworkType("android/net/DnsResolverHelper"))
    }

    @Test
    fun classifiesLibraryAndAndroidNetworkTypes() {
        assertTrue(NetworkApiRules.isNetworkType("okhttp3/OkHttpClient"))
        assertTrue(NetworkApiRules.isNetworkType("okhttp3/OkHttpClient\$Builder"))
        assertTrue(NetworkApiRules.isNetworkType("android/webkit/WebView"))
        assertTrue(NetworkApiRules.isNetworkType("android/location/Geocoder"))
        assertTrue(NetworkApiRules.isNetworkType("java/net/http/HttpClient"))
        assertFalse(NetworkApiRules.isNetworkType("okhttp3/Request"))
        assertFalse(NetworkApiRules.isNetworkType("okhttp3/Call\$Factory"))
        assertFalse(NetworkApiRules.isNetworkType("java/net/URI"))
        assertFalse(NetworkApiRules.isNetworkType("android/net/Uri"))
    }
}
