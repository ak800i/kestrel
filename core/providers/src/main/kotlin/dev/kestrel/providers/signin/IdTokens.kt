package dev.kestrel.providers.signin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/** The claims of a checked ID token that Kestrel keeps: the account's opaque `sub`, which toString never prints. */
data class IdTokenClaims(val subject: String) {
    override fun toString(): String = "IdTokenClaims(subject=set)"
}

/**
 * Checks the ChatGPT plan's ID token (design D9; OpenAI's "Registration and sign-in", whose signature example on "On
 * your website" uses OpenAI's published key set with the `jose` library and names no algorithm). Kestrel accepts only
 * RS256 with the RSA key whose `kid` matches the token's header, and refuses `none`, every HMAC algorithm and any other.
 */
object IdTokens {
    private const val CLOCK_SKEW_MILLIS = 60_000L

    /** The `kid` of the token's header, or null; the caller fetches the key set once more when it is not in the set. */
    fun keyId(idToken: String): String? = part(idToken, 0)?.string("kid")

    /**
     * The claims, or null when any check fails: the signature, `iss` equal to [issuer], `aud` (a string or an array) that
     * holds [audience], `exp` in the future with 60 s allowed for clock difference, `nonce` equal to [nonce], and a `sub`.
     */
    fun verify(idToken: String, keys: JsonObject, issuer: String, audience: String, nonce: String, nowMillis: Long): IdTokenClaims? {
        val parts = idToken.split('.')
        if (parts.size != 3) return null
        val header = part(idToken, 0) ?: return null
        if (header.string("alg") != "RS256") return null
        val kid = header.string("kid")
        val key = (keys["keys"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.firstOrNull {
            it.string("kid") == kid && it.string("kty") == "RSA" && it.string("alg").let { alg -> alg == null || alg == "RS256" } &&
                it.string("use").let { use -> use == null || use == "sig" }
        } ?: return null
        if (!signed(parts, key)) return null
        val claims = part(idToken, 1) ?: return null
        if (claims.string("iss") != issuer) return null
        val aud = claims["aud"]
        val audienceOk = (aud as? JsonPrimitive)?.takeIf { it.isString }?.content == audience ||
            (aud as? JsonArray)?.any { (it as? JsonPrimitive)?.content == audience } == true
        if (!audienceOk) return null
        val expiry = (claims["exp"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return null
        if ((expiry * 1000).toLong() + CLOCK_SKEW_MILLIS <= nowMillis) return null
        if (claims.string("nonce") != nonce) return null
        val subject = claims.string("sub")?.takeIf { it.isNotEmpty() } ?: return null
        return IdTokenClaims(subject)
    }

    private fun signed(parts: List<String>, jwk: JsonObject): Boolean =
        try {
            val modulus = BigInteger(1, Base64.getUrlDecoder().decode(jwk.string("n") ?: return false))
            val exponent = BigInteger(1, Base64.getUrlDecoder().decode(jwk.string("e") ?: return false))
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
            Signature.getInstance("SHA256withRSA").run {
                initVerify(publicKey)
                update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
                verify(Base64.getUrlDecoder().decode(parts[2]))
            }
        } catch (e: IllegalArgumentException) {
            false
        } catch (e: GeneralSecurityException) {
            false
        }

    private fun part(token: String, index: Int): JsonObject? {
        val text = token.split('.').getOrNull(index) ?: return null
        return try {
            Json.parseToJsonElement(Base64.getUrlDecoder().decode(text).decodeToString()) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
