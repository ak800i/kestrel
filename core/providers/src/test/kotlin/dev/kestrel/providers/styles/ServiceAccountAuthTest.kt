package dev.kestrel.providers.styles

import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.http.RefreshResult
import dev.kestrel.providers.testing.RedirectingCalls
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.TimeUnit

class ServiceAccountAuthTest {
    private val bench = AdapterHarness()
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val pem = pemOf(keys.private)
    private val key = Credential.ServiceAccountKey("kestrel@test-project.iam.gserviceaccount.com", pem, "key-1", "test-project")
    private var now = 1_800_000_000_000L
    private val tokens = ServiceAccountTokens()
    private val auth = ServiceAccountAuth(key, bench.http, { now }, tokens)
    private val anyRequest = HttpRequest("GET", "https://aiplatform.googleapis.com/v1/x".toHttpUrl())

    @After
    fun close() = bench.close()

    private fun pemOf(privateKey: PrivateKey): String =
        "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(privateKey.encoded) + "\n-----END PRIVATE KEY-----\n"

    private fun decode(part: String): JsonObject = Json.parseToJsonElement(Base64.getUrlDecoder().decode(part).decodeToString()) as JsonObject

    /** The JWT's header and claims of a recorded token request. */
    private fun jwtOf(recorded: RecordedRequest): Pair<JsonObject, JsonObject> {
        val (header, claims) = recorded.body!!.utf8().substringAfter("assertion=").split('.')
        return decode(header) to decode(claims)
    }

    private fun httpDate(millis: Long): String = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC))

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

    @Test
    fun aTokenRequestThatFailsOrGivesNoTokenKeepsTheKey() = runBlocking {
        bench.server.enqueue(MockResponse.Builder().body("{}").headersDelay(1, TimeUnit.SECONDS).build())
        bench.status(200, """{"token_type":"Bearer"}""", "Content-Type" to "application/json")
        val impatient = ServiceAccountAuth(key, ProviderHttp(RedirectingCalls(bench.server, readTimeoutMillis = 200)), { now }, tokens)

        assertEquals(RefreshResult.FailedForNow(ProviderError.Timeout), impatient.refresh())
        assertEquals(RefreshResult.FailedForNow(ProviderError.Malformed), auth.refresh())
        assertEquals(0, tokens.size)
    }

    @Test
    fun aRateLimitReadsRetryAfterAsProviderHttpDoes() = runBlocking {
        val limited = """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED"}}"""
        bench.status(429, limited, "Retry-After" to "-5")
        bench.status(429, limited, "Retry-After" to httpDate(now + 7_000))
        val timed = ServiceAccountAuth(key, ProviderHttp(bench.calls, clock = { now }), { now }, tokens)

        assertEquals(RefreshResult.FailedForNow(ProviderError.RateLimited(null, "RESOURCE_EXHAUSTED")), timed.refresh())
        assertEquals(RefreshResult.FailedForNow(ProviderError.RateLimited(7_000, "RESOURCE_EXHAUSTED")), timed.refresh())
    }

    @Test
    fun aClockFarOffIsCorrectedOnceByTheDateOfGooglesRefusal() = runBlocking {
        bench.status(400, bench.fixture("vertex/token-invalid-grant.json"), "Date" to httpDate(now + 3_600_000))
        bench.reply("vertex/token.json")

        assertEquals(mapOf("Authorization" to "Bearer ya29.test-token-1"), auth.headers(anyRequest))

        assertEquals(2, bench.server.requestCount)
        assertEquals(1_800_000_000L - 60, jwtOf(bench.take()).second["iat"]!!.jsonPrimitive.long)
        val (_, corrected) = jwtOf(bench.take())
        assertEquals(1_800_003_600L - 60, corrected["iat"]!!.jsonPrimitive.long)
        assertEquals(1_800_003_600L - 60 + 3600, corrected["exp"]!!.jsonPrimitive.long)
    }

    @Test
    fun aSecondRefusalAgreeingClocksOrAnotherErrorStillMeanANewKeyFile() = runBlocking {
        val invalidGrant = bench.fixture("vertex/token-invalid-grant.json")
        bench.status(400, invalidGrant, "Date" to httpDate(now + 3_600_000))
        bench.status(400, invalidGrant, "Date" to httpDate(now + 3_600_000))
        bench.status(400, invalidGrant, "Date" to httpDate(now - 120_000))
        bench.status(401, """{"error":"invalid_client"}""", "Date" to httpDate(now + 3_600_000))

        assertEquals(RefreshResult.Expired, auth.refresh())
        assertEquals(2, bench.server.requestCount)
        assertEquals(RefreshResult.Expired, auth.refresh())
        assertEquals(3, bench.server.requestCount)
        assertEquals(RefreshResult.Expired, auth.refresh())
        assertEquals(4, bench.server.requestCount)
    }

    @Test
    fun theCacheDropsTheLeastRecentlyUsedToken() = runBlocking {
        repeat(4) { bench.reply("vertex/token.json") }
        val two = ServiceAccountTokens(capacity = 2)
        fun account(n: Int) = ServiceAccountAuth(key.copy(clientEmail = "account-$n@test-project.iam.gserviceaccount.com"), bench.http, { now }, two)

        account(1).headers(anyRequest)
        account(2).headers(anyRequest)
        account(1).headers(anyRequest)
        account(3).headers(anyRequest)
        account(1).headers(anyRequest)
        assertEquals(3, bench.server.requestCount)
        account(2).headers(anyRequest)
        assertEquals(4, bench.server.requestCount)
    }

    @Test
    fun aKeyWithoutAnIdSendsNoKidAndIsToldApartByTheKeyItself() = runBlocking {
        repeat(2) { bench.reply("vertex/token.json") }
        val withoutId = key.copy(privateKeyId = null)
        val otherPem = pemOf(KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private)

        ServiceAccountAuth(withoutId, bench.http, { now }, tokens).headers(anyRequest)
        ServiceAccountAuth(withoutId, bench.http, { now }, tokens).headers(anyRequest)
        assertEquals(1, bench.server.requestCount)
        ServiceAccountAuth(withoutId.copy(privateKeyPem = otherPem), bench.http, { now }, tokens).headers(anyRequest)
        assertEquals(2, bench.server.requestCount)

        assertEquals(Json.parseToJsonElement("""{"alg":"RS256","typ":"JWT"}"""), jwtOf(bench.take()).first)
    }

    @Test
    fun callersThatWaitForAnExchangeUseItsToken() = runBlocking {
        repeat(2) { bench.reply("vertex/token.json") }

        val first = async { auth.headers(anyRequest) }
        val second = async { ServiceAccountAuth(key, bench.http, { now }, tokens).headers(anyRequest) }

        assertEquals(first.await(), second.await())
        assertEquals(1, bench.server.requestCount)
    }
}
