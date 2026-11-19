package dev.kestrel.providers.signin

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/** A key pair that the tests make, its key set as OpenAI publishes one, and ID tokens signed with it (design D19). */
internal class TestIdTokens(val kid: String = "test-kid") {
    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    /** The key set with this key, or with no key when [withKey] is false. */
    fun keySet(withKey: Boolean = true): String {
        if (!withKey) return """{"keys":[]}"""
        val key = pair.public as RSAPublicKey
        return """{"keys":[{"kty":"RSA","kid":"$kid","use":"sig","alg":"RS256","n":"${encode(key.modulus.toByteArray().dropWhile { it == 0.toByte() }.toByteArray())}","e":"${encode(key.publicExponent.toByteArray())}"}]}"""
    }

    /** A token with [claims], signed with this key under [alg] and [headerKid]; a [signature] replaces the real one. */
    fun token(claims: String, alg: String = "RS256", headerKid: String = kid, signature: String? = null): String {
        val head = encode("""{"alg":"$alg","kid":"$headerKid","typ":"JWT"}""".encodeToByteArray())
        val body = encode(claims.encodeToByteArray())
        val signed = signature ?: encode(
            Signature.getInstance("SHA256withRSA").run {
                initSign(pair.private)
                update("$head.$body".toByteArray(Charsets.US_ASCII))
                sign()
            },
        )
        return "$head.$body.$signed"
    }

    /** The claims of a valid token for [audience] and [nonce], expiring [expiresInSeconds] after [nowSeconds]. */
    fun claims(audience: String = "\"oaiapp_test\"", nonce: String = "test-nonce", nowSeconds: Long = NOW_SECONDS, expiresInSeconds: Long = 3600, issuer: String = "https://auth.openai.com", subject: String = "test-subject") =
        """{"iss":"$issuer","sub":"$subject","aud":$audience,"nonce":"$nonce","iat":$nowSeconds,"exp":${nowSeconds + expiresInSeconds}}"""

    companion object {
        const val NOW_SECONDS = 1_800_000_000L

        fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
