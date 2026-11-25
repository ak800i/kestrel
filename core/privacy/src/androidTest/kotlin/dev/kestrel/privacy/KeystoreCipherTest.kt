package dev.kestrel.privacy

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.privacy.crypto.AesGcmFieldCipher
import dev.kestrel.privacy.crypto.KeystoreErasableKey
import dev.kestrel.privacy.crypto.KeystoreKeys
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import javax.crypto.AEADBadTagException

@RunWith(AndroidJUnit4::class)
class KeystoreCipherTest {
    private val alias = "kestrel.test.${System.nanoTime()}"
    private val place = "settings/test".encodeToByteArray()

    @After
    fun deleteKey() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    @Test
    fun keystoreKeyDecryptsInANewCipherInstance() {
        val sealed = AesGcmFieldCipher { KeystoreKeys.getOrCreate(alias) }.encrypt("hello".encodeToByteArray(), place)

        val opened = AesGcmFieldCipher { KeystoreKeys.getOrCreate(alias) }.decrypt(sealed, place)

        assertEquals("hello", opened.decodeToString())
    }

    @Test
    fun valueTooShortToHoldTheTagIsRejectedAsAnUnknownFormat() {
        val cipher = AesGcmFieldCipher { KeystoreKeys.getOrCreate(alias) }
        val truncated = cipher.encrypt(ByteArray(0), place).copyOf(1 + 12 + 15)

        // Without the length check, Keystore throws IllegalBlockSizeException, which the stores don't catch.
        assertThrows(IllegalArgumentException::class.java) { cipher.decrypt(truncated, place) }
    }

    // Crypto-shredding (design 9.7 of sub-project 7): what the erased key encrypted fails its check under the new key.
    @Test
    fun anErasedKeyIsGoneFromKeystoreAndItsNextUseMakesANewOne() {
        val key = KeystoreErasableKey.of(alias)
        // One key object per alias and process, so that no part of Kestrel keeps a key that another part erased.
        assertSame(key, KeystoreErasableKey.of(alias))
        val sealed = AesGcmFieldCipher { key.get() }.encrypt("hello".encodeToByteArray(), place)
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        key.erase()
        assertFalse(keyStore.containsAlias(alias))
        // Erasing a key that is already gone does nothing.
        key.erase()

        assertThrows(AEADBadTagException::class.java) { AesGcmFieldCipher { key.get() }.decrypt(sealed, place) }
        assertTrue(keyStore.containsAlias(alias))
    }
}
