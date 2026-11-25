package dev.kestrel.privacy.crypto

import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts single database fields. The associated data binds a value to the place where it is stored. */
interface FieldCipher {
    fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray
    fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray
}

/**
 * AES-256-GCM (design spec, section 13.4). The output is a version byte, the 12-byte IV that the
 * cipher generates, and the ciphertext with its 16-byte tag.
 *
 * [key] gives the key at each use, or null when there is none, such as an erased key (design 9.7 of
 * sub-project 7): then a value in the known format fails its check, as under any other wrong key, and
 * nothing can be encrypted.
 */
class AesGcmFieldCipher(private val key: () -> SecretKey?) : FieldCipher {
    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // Android Keystore keys refuse caller-chosen IVs, so the cipher picks a random one.
        cipher.init(Cipher.ENCRYPT_MODE, checkNotNull(key()) { "No key to encrypt with" })
        cipher.updateAAD(associatedData)
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "Unexpected IV length ${iv.size}" }
        return byteArrayOf(VERSION) + iv + cipher.doFinal(plaintext)
    }

    override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
        // Keystore would report a too-short value as IllegalBlockSizeException, which also signals temporary failures.
        require(ciphertext.size >= HEADER_BYTES + TAG_BYTES && ciphertext[0] == VERSION) { "Unknown ciphertext format" }
        val key = key() ?: throw AEADBadTagException("No key")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, ciphertext, 1, IV_BYTES))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(ciphertext, HEADER_BYTES, ciphertext.size - HEADER_BYTES)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION: Byte = 1
        const val IV_BYTES = 12
        const val HEADER_BYTES = 1 + IV_BYTES
        const val TAG_BITS = 128
        const val TAG_BYTES = TAG_BITS / 8
    }
}
