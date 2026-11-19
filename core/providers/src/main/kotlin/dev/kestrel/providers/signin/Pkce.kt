package dev.kestrel.providers.signin

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** PKCE (RFC 7636) and the random values of OAuth sign-ins (design D9). */
object Pkce {
    private val random = SecureRandom()

    /** 32 random bytes, base64url without padding. */
    fun verifier(): String = randomText(32)

    /** The S256 challenge of [verifier]: its SHA-256, base64url without padding. */
    fun challenge(verifier: String): String =
        encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /** 16 random bytes, base64url without padding. */
    fun state(): String = randomText(16)

    /** 16 random bytes, base64url without padding. */
    fun nonce(): String = randomText(16)

    private fun randomText(bytes: Int): String = encode(ByteArray(bytes).also(random::nextBytes))

    private fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
