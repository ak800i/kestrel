package dev.kestrel.privacy

import android.content.pm.PackageManager
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.privacy.crypto.KeystoreKeys
import dev.kestrel.privacy.db.CredentialRow
import dev.kestrel.privacy.db.KestrelDatabase
import dev.kestrel.privacy.logs.ActivityKind
import dev.kestrel.privacy.settings.SettingKey
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

@RunWith(AndroidJUnit4::class)
class PrivacyStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = PrivacyStore(context)

    @Test
    fun aSettingChangedThroughAnotherStoreReachesTheObserver() = runBlocking {
        // A test-only name, so that the real safety settings keep test mode on during all testing (D25).
        val key = SettingKey("test.shared.${System.nanoTime()}", Int.serializer(), 0)
        val other = PrivacyStore(context)
        val values = Channel<Int>(Channel.UNLIMITED)
        val observer = launch { store.settings.observe(key).collect { values.send(it) } }
        try {
            withTimeout(5_000) {
                assertEquals(0, values.receive())
                other.settings.set(key, 7)
                assertEquals(7, values.receive())
            }
        } finally {
            observer.cancel()
        }
    }

    @Test
    fun anUnreadableSettingIsRecordedInTheActivityLog() = runBlocking {
        val name = "test.unreadable.${System.nanoTime()}"
        store.logs.deleteHistory()
        store.settings.set(SettingKey(name, Int.serializer(), 0), 7)

        // The stored JSON is the number 7, which cannot be read as text.
        assertEquals("fallback", store.settings.get(SettingKey(name, String.serializer(), "fallback")))

        val entry = store.logs.activity().single()
        assertEquals(ActivityKind.ERROR, entry.event.kind)
        assertEquals("The setting '$name' could not be read, so Kestrel uses its default", entry.event.text)
    }

    @Test
    fun credentialsUseTheirOwnKeyAndAnUnreadableOneStaysOutOfTheActivityLog() = runBlocking {
        // Test IDs, in this library's own test app, whose Keystore and database are not Kestrel's (design D15).
        val id = "test-credential-${System.nanoTime()}"
        val copy = "$id-copy"
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        // So that the key below is the one that this store made; only this test app's own test credentials used it.
        keyStore.deleteEntry(KeystoreKeys.CREDENTIALS_KEY)
        val before = store.logs.activity().size
        try {
            store.credentials.put(id, "key".encodeToByteArray())

            val key = keyStore.getKey(KeystoreKeys.CREDENTIALS_KEY, null) as SecretKey
            val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
            if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)) {
                assertEquals(KeyProperties.SECURITY_LEVEL_STRONGBOX, info.securityLevel)
            } else {
                assertNotEquals(KeyProperties.SECURITY_LEVEL_STRONGBOX, info.securityLevel)
            }

            val dao = KestrelDatabase.get(context).credentialsDao()
            dao.put(CredentialRow(copy, dao.get(id)!!.payload))
            assertNull(store.credentials.get(copy))
            assertEquals(before, store.logs.activity().size)
        } finally {
            store.credentials.delete(id)
            store.credentials.delete(copy)
        }
    }
}
