package dev.kestrel.providers.styles

import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.RefreshResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class ServiceAccountAuthTest {
    private val bench = AdapterHarness()
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keys.private.encoded) + "\n-----END PRIVATE KEY-----\n"
    private val key = Credential.ServiceAccountKey("kestrel@test-project.iam.gserviceaccount.com", pem, "key-1", "test-project")
    private var now = 1_800_000_000_000L
    private val tokens = ServiceAccountTokens()
    private val auth = ServiceAccountAuth(key, bench.http, { now }, tokens)
    private val anyRequest = HttpRequest("GET", "https://aiplatform.googleapis.com/v1/x".toHttpUrl())

    @After
    fun close() = bench.close()

    private fun decode(part: String): JsonObject = Json.parseToJsonElement(Base64.getUrlDecoder().decode(part).decodeToString()) as JsonObject

    @Test
    fun theSignedJwtIsExchangedOnceAndTheTokenReusedUntilAMinuteBeforeItEnds() = runBlocking {
        bench.reply("vertex/token.json")
        bench.reply("vertex/token.json")

        assertEquals(mapOf("Authorization" to "Bearer ya29.test-token-1"), auth.headers(anyRequest))
        now += 3_000_000
        auth.headers(anyRequest)
        assertEquals(1, bench.server.requestCount)
        now += 540_000
        auth.headers(anyRequest)
        assertEquals(2, bench.server.requestCount)

        assertEquals(listOf("https://oauth2.googleapis.com/token".toHttpUrl(), "https://oauth2.googleapis.com/token".toHttpUrl()), bench.calls.requests)
        val recorded = bench.take()
        assertEquals("application/x-www-form-urlencoded", recorded.headers["Content-Type"])
        val form = recorded.body!!.utf8()
        assertTrue(form.startsWith("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion="))
        val (header, claims, signature) = form.substringAfter("assertion=").split('.')
        assertEquals(Json.parseToJsonElement("""{"alg":"RS256","typ":"JWT","kid":"key-1"}"""), decode(header))
        val body = decode(claims)
        assertEquals("kestrel@test-project.iam.gserviceaccount.com", body["iss"]!!.jsonPrimitive.content)
        assertEquals("https://www.googleapis.com/auth/cloud-platform", body["scope"]!!.jsonPrimitive.content)
        assertEquals("https://oauth2.googleapis.com/token", body["aud"]!!.jsonPrimitive.content)
        assertEquals(1_800_000_000L - 60, body["iat"]!!.jsonPrimitive.long)
        assertEquals(1_800_000_000L - 60 + 3600, body["exp"]!!.jsonPrimitive.long)
        val verifier = Signature.getInstance("SHA256withRSA").apply {
            initVerify(keys.public)
            update("$header.$claims".encodeToByteArray())
        }
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(signature)))
    }

    @Test
    fun aNewInstanceForTheSameAccountReusesTheTokenAndTheCacheKeepsAFewTokensWithoutPrintingThem() = runBlocking {
        repeat(6) { bench.reply("vertex/token.json") }

        auth.headers(anyRequest)
        // Plan 4C builds a new instance for each request.
        assertEquals(mapOf("Authorization" to "Bearer ya29.test-token-1"), ServiceAccountAuth(key, bench.http, { now }, tokens).headers(anyRequest))
        assertEquals(1, bench.server.requestCount)
        // Another key of the same account gets its own token.
        ServiceAccountAuth(key.copy(privateKeyId = "key-2"), bench.http, { now }, tokens).headers(anyRequest)
        assertEquals(2, bench.server.requestCount)
        for (n in 2..5) ServiceAccountAuth(key.copy(clientEmail = "other-$n@test-project.iam.gserviceaccount.com"), bench.http, { now }, tokens).headers(anyRequest)
        assertEquals(4, tokens.size)
        assertEquals("ServiceAccountTokens(4 kept)", tokens.toString())
        assertTrue("ya29" !in auth.toString())
    }

    @Test
    fun aRefreshFetchesANewToken() = runBlocking {
        bench.reply("vertex/token.json")
        bench.reply("vertex/token.json")

        auth.headers(anyRequest)
        assertEquals(RefreshResult.Refreshed, auth.refresh())
        assertEquals(2, bench.server.requestCount)
    }

    @Test
    fun invalidGrantMeansANewKeyFileAndAServerErrorKeepsTheKey() = runBlocking {
        bench.status(400, bench.fixture("vertex/token-invalid-grant.json"))
        bench.status(503)
        bench.status(429, "", "Retry-After" to "4")

        assertEquals(RefreshResult.Expired, auth.refresh())
        assertEquals(RefreshResult.FailedForNow(ProviderError.ServerError(503)), auth.refresh())
        assertEquals(RefreshResult.FailedForNow(ProviderError.RateLimited(4_000)), auth.refresh())
    }

    @Test
    fun aRefusedKeyFailsTheRequestAsAnExpiredSignIn() {
        bench.status(400, bench.fixture("vertex/token-invalid-grant.json"))

        try {
            runBlocking { auth.headers(anyRequest) }
            fail("expected a ProviderException")
        } catch (e: ProviderException) {
            assertEquals(ProviderError.SignInExpired("google-vertex"), e.error)
        }
    }

    @Test
    fun aKeyThatIsNoKeyIsRefusedWithoutARequest() = runBlocking {
        val broken = ServiceAccountAuth(key.copy(privateKeyPem = "-----BEGIN PRIVATE KEY-----\nbm90IGEga2V5\n-----END PRIVATE KEY-----"), bench.http, { now }, tokens)

        assertEquals(RefreshResult.Expired, broken.refresh())
        assertEquals(0, bench.server.requestCount)
    }
}
