package dev.kestrel.privacy

import android.content.pm.PackageManager
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.privacy.credentials.CredentialStore
import dev.kestrel.privacy.crypto.AesGcmFieldCipher
import dev.kestrel.privacy.crypto.KeystoreKeys
import dev.kestrel.privacy.db.CredentialRow
import dev.kestrel.privacy.db.KestrelDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import javax.crypto.SecretKeyFactory

/**
 * The credential store with the device's Keystore (design spec, section 10.7). Every test uses its own key alias and a
 * database in memory, so the owner's credentials are never touched. KestrelDatabaseMigrationTest checks the migrations.
 */
@RunWith(AndroidJUnit4::class)
class CredentialStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val alias = "kestrel.test.credentials.${System.nanoTime()}"
    private val database = Room.inMemoryDatabaseBuilder(context, KestrelDatabase::class.java).build()
    private val unreadable = mutableListOf<String>()
    private val store = CredentialStore(database.credentialsDao(), AesGcmFieldCipher { KeystoreKeys.getOrCreate(alias) }, onUnreadable = { unreadable += it })

    @After
    fun cleanUp() {
        database.close()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    @Test
    fun aCredentialReadsBackUnderItsIdUntilItIsDeleted() = runBlocking {
        store.put("test-a", "first".encodeToByteArray())
        store.put("test-a", "second".encodeToByteArray())
        store.put("test-b", "other".encodeToByteArray())

        assertArrayEquals("second".encodeToByteArray(), store.get("test-a"))
        assertEquals(listOf("test-a", "test-b"), store.ids())
        store.delete("test-a")
        assertNull(store.get("test-a"))
        assertEquals(listOf("test-b"), store.ids())
    }

    @Test
    fun aPayloadMovedToAnotherIdIsDeletedAndReported() = runBlocking {
        store.put("test-a", "key".encodeToByteArray())
        val sealed = database.credentialsDao().get("test-a")!!.payload
        database.credentialsDao().put(CredentialRow("test-moved", sealed))

        assertNull(store.get("test-moved"))
        assertEquals(listOf("test-moved"), unreadable)
        assertEquals(listOf("test-a"), store.ids())
    }

    @Test
    fun theCredentialsKeyTakesTheDevicesStrongBoxPath() {
        val strongBox = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

        val key = KeystoreKeys.getOrCreate(alias, strongBox)

        val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
        if (strongBox) {
            assertEquals(KeyProperties.SECURITY_LEVEL_STRONGBOX, info.securityLevel)
        } else {
            assertNotEquals(KeyProperties.SECURITY_LEVEL_STRONGBOX, info.securityLevel)
        }
        assertEquals(256, info.keySize)
    }
}
