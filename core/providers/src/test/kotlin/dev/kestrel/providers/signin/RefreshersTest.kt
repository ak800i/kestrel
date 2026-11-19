package dev.kestrel.providers.signin

import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.signin.SignInFixtures.fixture
import dev.kestrel.providers.signin.SignInFixtures.json
import dev.kestrel.providers.testing.RedirectingCalls
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** The refreshers of the ChatGPT plan, xAI and MiniMax against MockWebServer (design D9 and D19). */
class RefreshersTest {
    private val server = MockWebServer()
    private lateinit var calls: RedirectingCalls
    private lateinit var http: ProviderHttp
    private val now = 1_000_000_000_000L

    @Before
    fun start() {
        server.start()
        calls = RedirectingCalls(server)
        http = ProviderHttp(calls)
    }

    @After
    fun stop() {
        server.close()
    }

    private val plan = Credential.OAuthToken("test-access-1", "test-refresh-1", now + 10_000, clientId = "oaiapp_test")
    private val xai = Credential.OAuthToken("test-xai-access-1", "test-xai-refresh-1", now, tokenEndpoint = "https://auth.x.ai/oauth2/token")
    private val miniMax = Credential.OAuthToken("test-minimax-access-1", "test-minimax-refresh-1", now, tokenEndpoint = MiniMaxRegion.GLOBAL.tokenUrl)

    @Test
    fun theChatGptPlanRotatesTheRefreshTokenAndReadsEarliestRefreshAt() = runBlocking {
        server.enqueue(fixture(200, "chatgpt-plan/refresh.json"))

        val result = ChatGptPlanRefresher(http) { now }.refresh(plan)

        val token = (result as Refresh.Done).token
        assertEquals("test-access-2", token.access)
        assertEquals("test-refresh-2", token.refresh)
        assertEquals(now + 3_600_000, token.expiresAtMillis)
        assertEquals(1_800_000_000_000L, token.earliestRefreshAtMillis)
        assertEquals("oaiapp_test", token.clientId)
        assertEquals("https://auth.openai.com/api/accounts/oauth/token", calls.requests.single().toString())
        val form = server.takeRequest().body!!.utf8()
        assertEquals(
            "grant_type=refresh_token&client_id=oaiapp_test&refresh_token=test-refresh-1&resource=https%3A%2F%2Fapi.openai.com%2Fv1",
            form,
        )
    }

    @Test
    fun eachPermanentRefusalOfTheChatGptPlanEndsTheSignInAndKeepsTheClientId() = runBlocking {
        val codes = listOf(
            "invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired", "refresh_token_invalidated",
            "refresh_token_reused",
        )
        for (code in codes) server.enqueue(json(400, """{"error":"$code"}"""))

        for (code in codes) assertEquals(code, Refresh.Refused(), ChatGptPlanRefresher(http) { now }.refresh(plan))
    }

    @Test
    fun invalidClientClearsTheChatGptPlansClientId() = runBlocking {
        server.enqueue(json(401, """{"error":"invalid_client"}"""))

        val result = ChatGptPlanRefresher(http) { now }.refresh(plan)

        assertEquals(Refresh.Refused(plan.copy(clientId = null)), result)
    }

    @Test
    fun aRateLimitOrAServerErrorKeepsTheSignIn() = runBlocking {
        server.enqueue(json(429, """{"error":"slow_down"}""", "Retry-After" to "7"))
        server.enqueue(json(503, "{}"))

        // auth.openai.com's 429 is not the plan's usage limit.
        assertEquals(Refresh.ForNow(ProviderError.ServerError(503)), ChatGptPlanRefresher(http) { now }.refresh(plan))
        assertEquals(Refresh.ForNow(ProviderError.ServerError(503)), ChatGptPlanRefresher(http) { now }.refresh(plan))
    }

    @Test
    fun xaiTriesACloudflareChallengeThreeTimesAndKeepsTheOldRefreshToken() = runBlocking {
        repeat(3) { server.enqueue(json(403, "{}", "cf-mitigated" to "challenge")) }
        server.enqueue(fixture(200, "xai/refresh.json"))
        val pauses = mutableListOf<Long>()

        val result = XaiRefresher(http, { now }) { pauses += it }.refresh(xai)

        val token = (result as Refresh.Done).token
        assertEquals("test-xai-access-2", token.access)
        assertEquals("test-xai-refresh-1", token.refresh)
        assertEquals(now + 3_600_000, token.expiresAtMillis)
        assertEquals(listOf(250L, 250L, 250L), pauses)
        assertEquals(4, calls.requests.size)
        repeat(3) { server.takeRequest() }
        assertEquals(
            "grant_type=refresh_token&client_id=b1a00492-073a-47ea-816f-4c329264a828&refresh_token=test-xai-refresh-1",
            server.takeRequest().body!!.utf8(),
        )
    }

    @Test
    fun xaisFinalRefusalsEndTheSignInAndATokenEndpointOffXaiIsNeverCalled() = runBlocking {
        for (code in listOf("invalid_request", "access_denied", "invalid_grant")) server.enqueue(json(400, """{"error":"$code"}"""))

        repeat(3) { assertEquals(Refresh.Refused(), XaiRefresher(http, { now }) {}.refresh(xai)) }
        assertEquals(Refresh.Refused(), XaiRefresher(http, { now }) {}.refresh(xai.copy(tokenEndpoint = "https://auth.example.com/token")))
        assertEquals(3, calls.requests.size)
    }

    @Test
    fun miniMaxReadsItsExpiryAndEndsTheSignInOnAnErrorStatus() = runBlocking {
        server.enqueue(fixture(200, "minimax/refresh.json"))
        server.enqueue(json(200, """{"status":"error","base_resp":{"status_code":1004,"status_msg":"expired"}}"""))
        server.enqueue(json(200, """{"access_token":"x","base_resp":{"status_code":2013}}"""))

        val token = (MiniMaxRefresher(http, { now }, MiniMaxRegion.GLOBAL).refresh(miniMax) as Refresh.Done).token
        assertEquals("test-minimax-access-2", token.access)
        assertEquals("test-minimax-refresh-1", token.refresh)
        assertEquals(now + 7_200_000, token.expiresAtMillis)
        assertEquals(
            "grant_type=refresh_token&client_id=78257093-7e40-4613-99e0-527b14b39113&refresh_token=test-minimax-refresh-1",
            server.takeRequest().body!!.utf8(),
        )
        assertEquals(Refresh.Refused(), MiniMaxRefresher(http, { now }).refresh(miniMax))
        assertEquals(Refresh.Refused(), MiniMaxRefresher(http, { now }).refresh(miniMax))
        // A global token on a profile of MiniMax's China region is refused without a request.
        assertEquals(Refresh.Refused(), MiniMaxRefresher(http, { now }, MiniMaxRegion.CHINA).refresh(miniMax))
        assertEquals(3, calls.requests.size)
        assertTrue(calls.requests.all { it.toString() == "https://account.minimax.io/oauth2/token" })
    }

    @Test
    fun anAnswerThatIsNotOneOfD9sFinalRefusalsFailsForNow() = runBlocking {
        server.enqueue(json(403, "<html>blocked</html>"))
        server.enqueue(json(400, """{"error":"temporarily_unavailable"}"""))
        server.enqueue(json(403, "{}"))
        server.enqueue(json(400, """{"error":"unsupported_grant_type"}"""))
        server.enqueue(json(200, "not json"))
        server.enqueue(json(401, "{}"))
        server.enqueue(json(400, """{"status":"error"}"""))

        assertEquals(Refresh.ForNow(ProviderError.BadRequest(403, null)), ChatGptPlanRefresher(http) { now }.refresh(plan))
        assertEquals(Refresh.ForNow(ProviderError.BadRequest(400, null)), ChatGptPlanRefresher(http) { now }.refresh(plan))
        // A 403 without Cloudflare's challenge header is not a challenge, and not a final refusal either.
        assertEquals(Refresh.ForNow(ProviderError.BadRequest(403, null)), XaiRefresher(http, { now }) {}.refresh(xai))
        assertEquals(Refresh.ForNow(ProviderError.BadRequest(400, null)), XaiRefresher(http, { now }) {}.refresh(xai))
        assertEquals(Refresh.ForNow(ProviderError.Malformed), MiniMaxRefresher(http, { now }).refresh(miniMax))
        assertEquals(Refresh.ForNow(ProviderError.BadRequest(401, null)), MiniMaxRefresher(http, { now }).refresh(miniMax))
        // MiniMax's error status is final whatever the HTTP status.
        assertEquals(Refresh.Refused(), MiniMaxRefresher(http, { now }).refresh(miniMax))
    }

    @Test
    fun retryAfterIsReadAsAnHttpDateAndANegativeValueIsNone() = runBlocking {
        val timed = ProviderHttp(calls, clock = { now })
        val date = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(now + 30_000).atZone(ZoneOffset.UTC))
        server.enqueue(json(429, "{}", "Retry-After" to date))
        server.enqueue(json(429, "{}", "Retry-After" to "-5"))

        assertEquals(Refresh.ForNow(ProviderError.RateLimited(30_000)), XaiRefresher(timed, { now }) {}.refresh(xai))
        assertEquals(Refresh.ForNow(ProviderError.RateLimited(null)), MiniMaxRefresher(timed, { now }).refresh(miniMax))
    }
}
