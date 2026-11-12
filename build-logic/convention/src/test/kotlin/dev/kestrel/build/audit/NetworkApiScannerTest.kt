package dev.kestrel.build.audit

import dev.kestrel.build.audit.fixtures.CreatesSocket
import dev.kestrel.build.audit.fixtures.Harmless
import dev.kestrel.build.audit.fixtures.JavaMethodReference
import dev.kestrel.build.audit.fixtures.MentionsSocketClass
import dev.kestrel.build.audit.fixtures.OpensUrl
import dev.kestrel.build.audit.fixtures.PassesSocket
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
