package dev.kestrel.providers.signin

import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.signin.SignInFixtures.json
import dev.kestrel.providers.signin.SignInFixtures.read
import dev.kestrel.providers.testing.RedirectingCalls
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder

/** MiniMax's user-code grant with PKCE and its echoed state (design D9; research r31). */
class MiniMaxDeviceFlowTest {
    private val server = MockWebServer()
    private lateinit var calls: RedirectingCalls
    private lateinit var http: ProviderHttp
    private var now = 1_000_000L
    private val pauses = mutableListOf<Long>()

    /** The answers to the polls, in order; the code request is answered with its own state unless [stateBack] is set. */
    private val polls = ArrayDeque<MockResponse>()
    private var codeAnswer = read("minimax/device-code.json")
    private var stateBack: String? = null
    private val forms = mutableListOf<Map<String, String>>()

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val form = request.body!!.utf8().split('&').associate { field ->
                    val (name, value) = field.split('=', limit = 2)
                    name to URLDecoder.decode(value, Charsets.UTF_8)
                }
                forms += form
                return if (request.url.encodedPath == "/oauth2/device/code") {
                    json(200, codeAnswer.replace("STATE", stateBack ?: form.getValue("state")))
                } else {
                    polls.removeFirst()
                }
            }
        }
        server.start()
        calls = RedirectingCalls(server)
        http = ProviderHttp(calls)
    }

    @After
    fun stop() {
        server.close()
    }

    private fun flow(region: MiniMaxRegion = MiniMaxRegion.GLOBAL) = MiniMaxDeviceFlow(region, http, { now }) { pauses += it; now += it }

    private fun failureOf(block: suspend () -> Unit): SignInFailure =
        try {
            runBlocking { block() }
            fail("expected SignInFailed")
            throw AssertionError()
        } catch (e: SignInFailed) {
            e.kind
        }

    @Test
    fun aSignInSendsPkceAndItsStateReadsTheIntervalAsMillisecondsAndKeepsTheResourceUrl() = runBlocking {
        // A pending answer may carry a base_resp of its own; during the sign-in only the status decides.
        polls += json(200, """{"status":"pending","base_resp":{"status_code":1001,"status_msg":"pending"}}""")
        polls += json(200, read("minimax/token.json"))
        val flow = flow()

        val prompt = flow.start()
        val result = flow.await(prompt)

        assertEquals(DeviceCodePrompt("MM-1234", "https://www.minimax.io/oauth/device?user_code=MM-1234", 1_000_000L + 600_000, 3_000), prompt)
        assertEquals(
            Credential.OAuthToken(
                "test-minimax-access-1", "test-minimax-refresh-1", now + 7_200_000,
                tokenEndpoint = "https://account.minimax.io/oauth2/token", region = "global",
            ),
            result.credential,
        )
        assertEquals("https://api.minimax.io/anthropic", result.baseUrl)
        assertEquals(setOf("account.minimax.io", "api.minimax.io"), result.hosts)
        assertEquals(listOf(3_000L, 3_000L), pauses)
        val code = forms.first()
        assertEquals("78257093-7e40-4613-99e0-527b14b39113", code["client_id"])
        assertEquals("S256", code["code_challenge_method"])
        assertEquals("group_id profile model.completion", code["scope"])
        val poll = forms.last()
        assertEquals("urn:ietf:params:oauth:grant-type:user_code", poll["grant_type"])
        assertEquals("MM-1234", poll["user_code"])
        assertEquals(code["code_challenge"], Pkce.challenge(poll.getValue("code_verifier")))
        assertTrue(server.takeRequest().headers["x-request-id"]!!.isNotEmpty())
    }

    @Test
    fun aChangedStateIsRefusedAndAForeignResourceUrlFallsBackToTheRegionsRoute() = runBlocking {
        stateBack = "another-state"
        assertEquals(SignInFailure.EXCHANGE_FAILED, failureOf { flow().start() })

        stateBack = null
        codeAnswer = read("minimax/device-code.json").replace("3000", "3")
        polls += json(200, read("minimax/token.json").replace("https://api.minimax.io/anthropic", "https://api.example.com/anthropic"))
        val flow = flow(MiniMaxRegion.CHINA)
        val prompt = flow.start()
        val result = flow.await(prompt)

        assertEquals(3_000L, prompt.intervalMillis)
        assertEquals("https://api.minimaxi.com/anthropic", result.baseUrl)
        assertEquals("https://account.minimaxi.com/oauth2/token", calls.requests.last().toString())
        // An interval of 1 s is raised to the floor of 2 s, because MiniMax has no slow_down.
        codeAnswer = read("minimax/device-code.json").replace("3000", "1")
        assertEquals(2_000L, flow().start().intervalMillis)
    }

    @Test
    fun anErrorStatusIsARefusalAndTheDeadlineEndsTheSignIn() {
        polls += json(200, """{"status":"error","base_resp":{"status_code":1004,"status_msg":"denied"}}""")
        assertEquals(SignInFailure.REFUSED, failureOf { flow().let { it.await(it.start()) } })
        // A refused HTTP status ends the sign-in whatever its body says.
        polls += json(400, """{"status":"pending"}""")
        assertEquals(SignInFailure.REFUSED, failureOf { flow().let { it.await(it.start()) } })

        codeAnswer = read("minimax/device-code.json").replace("https://www.minimax.io/", "https://www.example.com/")
        assertEquals(SignInFailure.UNTRUSTED_ADDRESS, failureOf { flow().start() })

        codeAnswer = read("minimax/device-code.json").replace("\"expired_in\": 600", "\"expired_in\": 2")
        assertEquals(SignInFailure.EXPIRED, failureOf { flow().let { it.await(it.start()) } })
    }

    @Test
    fun aRateLimitOrAServerErrorDuringAPollSlowsThePollsDown() = runBlocking {
        polls += json(503, "{}")
        polls += json(429, "{}")
        polls += json(200, read("minimax/token.json"))
        val flow = flow()

        val result = flow.await(flow.start())

        assertEquals("test-minimax-access-1", (result.credential as Credential.OAuthToken).access)
        assertEquals(listOf(3_000L, 8_000L, 13_000L), pauses)
    }

    @Test
    fun aResourceUrlWithUserQueryFragmentOrOnTheOtherRegionFallsBackToTheRegionsRoute() = runBlocking {
        val refused = listOf(
            "https://user@api.minimax.io/anthropic", "https://api.minimax.io/anthropic?a=1", "https://api.minimax.io/anthropic#a",
            "https://api.minimaxi.com/anthropic",
        )
        for (address in refused) {
            polls += json(200, read("minimax/token.json").replace("https://api.minimax.io/anthropic", address))
            val flow = flow()
            assertEquals(address, "https://api.minimax.io/anthropic", flow.await(flow.start()).baseUrl)
        }
        polls += json(200, read("minimax/token.json").replace("https://api.minimax.io/anthropic", "https://api.minimaxi.com/anthropic/v2"))
        val china = flow(MiniMaxRegion.CHINA)
        assertEquals("https://api.minimaxi.com/anthropic/v2", china.await(china.start()).baseUrl)
    }
}
