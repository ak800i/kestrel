package dev.kestrel.privacy.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** AES-256 keys that never leave Android Keystore (design spec, section 13.4). */
object KeystoreKeys {
    /** Encrypts logs and settings. */
    const val DATA_KEY = "kestrel.data"

    /** Encrypts the providers' credentials (design spec, section 10.7). */
    const val CREDENTIALS_KEY = "kestrel.credentials"

    private const val PROVIDER = "AndroidKeyStore"

    /**
     * Returns the key [alias] and makes it when it does not exist yet. With [strongBox], a new key is made in StrongBox, or
     * without it when Keystore reports that StrongBox is not available.
     */
    @Synchronized
    fun getOrCreate(alias: String, strongBox: Boolean = false): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getKey(alias, null) as SecretKey?)?.let { return it }
        if (strongBox) {
            try {
                return generate(alias, strongBox = true)
            } catch (e: StrongBoxUnavailableException) {
                // The device announced StrongBox but cannot make this key there; it is made without StrongBox below.
            }
        }
        return generate(alias, strongBox = false)
    }

    private fun generate(alias: String, strongBox: Boolean): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setIsStrongBoxBacked(strongBox)
                .build(),
        )
        return generator.generateKey()
    }
}
