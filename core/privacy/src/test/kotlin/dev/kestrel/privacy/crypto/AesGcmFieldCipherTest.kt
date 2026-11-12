package dev.kestrel.privacy.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator

class AesGcmFieldCipherTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = AesGcmFieldCipher { key }
    private val place = "settings/safety".encodeToByteArray()

    @Test
    fun decryptsWhatItEncrypted() {
        val sealed = cipher.encrypt("hello".encodeToByteArray(), place)
        assertEquals("hello", cipher.decrypt(sealed, place).decodeToString())
    }

    @Test
    fun encryptingTheSameValueTwiceGivesDifferentBytes() {
        val first = cipher.encrypt("hello".encodeToByteArray(), place)
        val second = cipher.encrypt("hello".encodeToByteArray(), place)
        assertFalse(first.contentEquals(second))
    }

    @Test
    fun outputHasVersionByteAndTwelveByteIv() {
        val sealed = cipher.encrypt(ByteArray(10), place)
        assertEquals(1.toByte(), sealed[0])
        assertEquals(1 + 12 + 10 + 16, sealed.size)
    }

    @Test
    fun valueMovedToAnotherPlaceDoesNotDecrypt() {
        val sealed = cipher.encrypt("hello".encodeToByteArray(), place)
        assertThrows(AEADBadTagException::class.java) { cipher.decrypt(sealed, "settings/other".encodeToByteArray()) }
    }

    @Test
    fun tamperedValueDoesNotDecrypt() {
        val sealed = cipher.encrypt("hello".encodeToByteArray(), place)
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()
        assertThrows(AEADBadTagException::class.java) { cipher.decrypt(sealed, place) }
    }

    @Test
    fun unknownFormatIsRejected() {
        val sealed = cipher.encrypt("hello".encodeToByteArray(), place)
        sealed[0] = 2
        val error = assertThrows(IllegalArgumentException::class.java) { cipher.decrypt(sealed, place) }
        assertEquals("Unknown ciphertext format", error.message)
    }

    @Test
    fun emptyValueRoundTrips() {
        assertArrayEquals(ByteArray(0), cipher.decrypt(cipher.encrypt(ByteArray(0), place), place))
    }
}
