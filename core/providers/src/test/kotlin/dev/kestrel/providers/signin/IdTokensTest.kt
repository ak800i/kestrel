package dev.kestrel.providers.signin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The checks of the ChatGPT plan's ID token (design D9 and D19), with a key pair that the test makes. */
class IdTokensTest {
    private val keys = TestIdTokens()
    private val keySet = Json.parseToJsonElement(keys.keySet()) as JsonObject
    private val now = TestIdTokens.NOW_SECONDS * 1000

    private fun verify(token: String, set: JsonObject = keySet) =
        IdTokens.verify(token, set, "https://auth.openai.com", "oaiapp_test", "test-nonce", now)

    @Test
    fun aValidTokenGivesItsSubjectAlsoWithAnAudienceArrayOrAnExpiry30SecondsAgo() {
        assertEquals(IdTokenClaims("test-subject"), verify(keys.token(keys.claims())))
        assertEquals(IdTokenClaims("test-subject"), verify(keys.token(keys.claims(audience = """["other","oaiapp_test"]"""))))
        assertEquals(IdTokenClaims("test-subject"), verify(keys.token(keys.claims(expiresInSeconds = -30))))
        assertEquals("test-kid", IdTokens.keyId(keys.token(keys.claims())))
    }

    @Test
    fun aWrongSignatureIssuerAudienceExpiryOrNonceFails() {
        val other = TestIdTokens()
        assertNull(verify(keys.token(keys.claims(), signature = TestIdTokens.encode(ByteArray(256)))))
        assertNull(verify(other.token(keys.claims())))
        assertNull(verify(keys.token(keys.claims(issuer = "https://auth.example.com"))))
        assertNull(verify(keys.token(keys.claims(audience = "\"oaiapp_other\""))))
        assertNull(verify(keys.token(keys.claims(expiresInSeconds = -120))))
        assertNull(verify(keys.token(keys.claims(nonce = "another-nonce"))))
        // An authorized party that is another client, as OpenID Connect Core 3.1.3.7 asks; the client's own one passes.
        assertNull(verify(keys.token(keys.claims().replace("\"iat\"", "\"azp\":\"oaiapp_other\",\"iat\""))))
        assertEquals(IdTokenClaims("test-subject"), verify(keys.token(keys.claims().replace("\"iat\"", "\"azp\":\"oaiapp_test\",\"iat\""))))
    }

    @Test
    fun noneHmacAndAnUnknownKidFail() {
        // Signed with the real key, so that only the rule of RS256 refuses it.
        assertNull(verify(keys.token(keys.claims(), alg = "none")))
        assertNull(verify(keys.token(keys.claims(), alg = "HS256")))
        assertNull(verify(keys.token(keys.claims(), headerKid = "unknown-kid")))
        assertNull(verify(keys.token(keys.claims()), Json.parseToJsonElement(keys.keySet(withKey = false)) as JsonObject))
    }
}
