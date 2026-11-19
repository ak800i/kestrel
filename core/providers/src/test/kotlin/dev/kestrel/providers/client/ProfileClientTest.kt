package dev.kestrel.providers.client

import dev.kestrel.providers.catalog.AuthMethod
import dev.kestrel.providers.catalog.LocalServerKind
import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.ChatAdapter
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.ChatResponse
import dev.kestrel.providers.chat.ModelTarget
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.chat.StopReason
import dev.kestrel.providers.chat.Usage
import dev.kestrel.providers.cost.Price
import dev.kestrel.providers.http.NoAuth
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.profiles.ModelRole
import dev.kestrel.providers.profiles.Profile
import dev.kestrel.providers.profiles.ProfileModel
import dev.kestrel.providers.profiles.ProvidersSetting
import dev.kestrel.providers.signin.SignInFixtures.catalog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

/** ProfileClient's limit per attempt, its retries, its checks before a request, and its cost (design D4, D11, D14, D17). */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileClientTest {
    private val request = ChatRequest("You are a test.", listOf(ChatMessage.User(listOf(Part.Text("Hi")))), maxOutputTokens = 100)
    private val answer = ChatResponse(ChatMessage.Assistant(listOf(AssistantPart.Text("OK"))), StopReason.END, Usage(1_000, 500), "test-model")
    private val spent = mutableListOf<Triple<String, Double, Long>>()
    private val calls = mutableListOf<Long>()

    private fun profile(provider: String = "anthropic", choice: String = "apiKey", method: AuthMethod = AuthMethod.API_KEY, base: String? = null) =
        Profile(
            "test-profile", "Test", provider, choice, method, "test-credential", baseUrl = base,
            localServer = if (base != null) LocalServerKind.OLLAMA else null,
            lightweight = ProfileModel("test-model", price = Price(input = 1.0, output = 2.0)),
            versatile = ProfileModel("test-model"),
        )

    private fun target(base: String = "https://api.anthropic.com") =
        ModelTarget("anthropic", RequestStyle.ANTHROPIC, base.toHttpUrl(), ModelEntry("test-model", "Test model"), auth = NoAuth)

    /** An adapter that runs one step per call, in order; each step answers, throws or waits. */
    private fun TestScope.adapter(vararg steps: suspend () -> ChatResponse) = object : ChatAdapter {
        private var next = 0

        override suspend fun complete(request: ChatRequest, target: ModelTarget): ChatResponse {
            calls += currentTime
            return steps[next++]()
        }
    }

    private fun client(
        profile: Profile,
        adapter: ChatAdapter,
        applied: MutableStateFlow<ProvidersSetting?> = MutableStateFlow(ProvidersSetting(listOf(profile))),
        target: ModelTarget = target(),
        lan: Boolean = true,
        limits: PlanLimits = NoLimits,
    ) = ProfileClient(
        profile, catalog, applied, { _, _ -> target }, { adapter }, { lan },
        spend = { id, _, credits, tokens -> spent += Triple(id, credits, tokens) }, limits = limits, clock = { 1_800_000_000_000L },
    )

    private object NoLimits : PlanLimits {
        override fun reached(profileId: String) = Unit

        override fun cleared(profileId: String) = Unit
    }

    private suspend fun errorOf(block: suspend () -> Unit): ProviderError =
        try {
            block()
            fail("expected a ProviderException")
            throw AssertionError()
        } catch (e: ProviderException) {
            e.error
        }

    @Test
    fun anAnswerIsPricedAndItsCreditsAndTokensAreAdded() = runTest {
        val priced = client(profile(), adapter({ answer })).complete(ModelRole.LIGHTWEIGHT, request)

        // 1,000 input tokens at $1 and 500 output tokens at $2 per million: $0.002, which is 0.2 credits.
        assertEquals(0.002, priced.cost.usd, 1e-9)
        assertEquals(0.2, priced.cost.credits, 1e-9)
        assertEquals(RequestStyle.ANTHROPIC, priced.style)
        assertEquals("test-profile", spent.single().first)
        assertEquals(0.2, spent.single().second, 1e-9)
        assertEquals(1_500L, spent.single().third)
    }

    @Test
    fun anAttemptThatRunsOutOfTimeIsATimeoutAndIsTriedAgainAfterOneSecond() = runTest {
        val priced = client(profile(), adapter({ delay(31_000); answer }, { answer })).complete(ModelRole.LIGHTWEIGHT, request)

        assertEquals(answer, priced.response)
        assertEquals(listOf(0L, 31_000L), calls)
    }

    @Test
    fun aServerErrorIsTriedTwiceAfterOneAndThreeSecondsAndThenFails() = runTest {
        val failing: suspend () -> ChatResponse = { throw ProviderException(ProviderError.ServerError(503)) }

        val error = errorOf { client(profile(), adapter(failing, failing, failing)).complete(ModelRole.LIGHTWEIGHT, request) }

        assertEquals(ProviderError.ServerError(503), error)
        assertEquals(listOf(0L, 1_000L, 4_000L), calls)
    }

    @Test
    fun aRetryAfterOfAtMostTenSecondsIsWaitedForAndALongerOneFailsAtOnce() = runTest {
        val short: suspend () -> ChatResponse = { throw ProviderException(ProviderError.RateLimited(8_000)) }
        val long: suspend () -> ChatResponse = { throw ProviderException(ProviderError.RateLimited(11_000, "rate_limit")) }

        client(profile(), adapter(short, { answer })).complete(ModelRole.LIGHTWEIGHT, request)
        val error = errorOf { client(profile(), adapter(long)).complete(ModelRole.LIGHTWEIGHT, request) }

        assertEquals(ProviderError.RateLimited(11_000, "rate_limit"), error)
        assertEquals(listOf(0L, 8_000L, 8_000L), calls)
    }

    @Test
    fun theChatGptPlansUsageLimitIsNotRetriedAndHoldsUntilALaterCallWorks() = runTest {
        val plan = profile("openai", "chatgpt-plan", AuthMethod.BROWSER)
        val applied = MutableStateFlow<ProvidersSetting?>(ProvidersSetting(listOf(plan)))
        val limit = ProviderError.QuotaOrPolicy(429, "subscription_sharing_usage_limit_exceeded")
        val adapter = adapter({ throw ProviderException(limit) }, { answer })
        val repository = ProvidersRepository(MutableStateFlow(applied.value), applied) { profile, limits ->
            client(profile, adapter, applied, limits = limits)
        }

        assertEquals(limit, errorOf { repository.client("test-profile")!!.complete(ModelRole.LIGHTWEIGHT, request) })
        assertEquals(setOf("test-profile"), repository.planLimitReached.value)
        assertEquals(1, calls.size)
        val priced = repository.client("test-profile")!!.complete(ModelRole.LIGHTWEIGHT, request)
        assertEquals(emptySet<String>(), repository.planLimitReached.value)
        // The plan pays for its requests, so they count tokens but no credits (design D14).
        assertTrue(priced.cost.planUsage)
        assertEquals(0.0, spent.single().second, 0.0)
        assertEquals(1_500L, spent.single().third)
    }

    @Test
    fun aCancelledCallerGetsACancellationException() = runTest {
        val call = async { client(profile(), adapter({ awaitCancellation() })).complete(ModelRole.LIGHTWEIGHT, request) }
        runCurrent()

        call.cancel()

        try {
            call.await()
            fail("expected a CancellationException")
        } catch (e: CancellationException) {
            assertEquals(1, calls.size)
        }
    }

    @Test
    fun aSwitchedOffSignInFailsAtOnce() = runTest {
        val xai = profile("xai", "xai-oauth", AuthMethod.DEVICE_CODE)

        val error = errorOf { client(xai, adapter({ answer })).complete(ModelRole.LIGHTWEIGHT, request) }

        assertEquals(ProviderError.SignInSwitchedOff("xai"), error)
        assertEquals(0, calls.size)
    }

    @Test
    fun aServerOnTheOwnersNetworkNeedsThePermissionAndOneOnThePhoneDoesNot() = runTest {
        val lan = profile("ollama", "ollama-server", AuthMethod.SERVER_ADDRESS, base = "http://192.168.1.20:11434")
        val phone = profile("ollama", "ollama-server", AuthMethod.SERVER_ADDRESS, base = "http://127.0.0.1:11434")

        val error = errorOf { client(lan, adapter({ answer }), target = target("http://192.168.1.20:11434"), lan = false).complete(ModelRole.LIGHTWEIGHT, request) }
        client(phone, adapter({ answer }), target = target("http://127.0.0.1:11434"), lan = false).complete(ModelRole.LIGHTWEIGHT, request)

        assertEquals(ProviderError.LocalNetworkNotAllowed, error)
        assertEquals(1, calls.size)
    }

    @Test
    fun theClientWaitsUntilTheAllowlistHoldsTheProfileAndFailsAfterFiveSeconds() = runTest {
        val applied = MutableStateFlow<ProvidersSetting?>(null)
        val call = async { client(profile(), adapter({ answer }), applied).complete(ModelRole.LIGHTWEIGHT, request) }
        advanceTimeBy(2_000)
        applied.value = ProvidersSetting(listOf(profile()))
        assertEquals(answer, call.await().response)

        val error = errorOf { client(profile(), adapter({ answer }), MutableStateFlow(ProvidersSetting())).complete(ModelRole.LIGHTWEIGHT, request) }
        assertEquals(ProviderError.NotAllowed, error)
        assertEquals(listOf(2_000L), calls)

        // A stored profile that gained a host, as after a new Copilot base, still holds this client's hosts, and the call
        // resolves the profile as the allowlist holds it.
        val moved = profile().copy(discoveredHosts = listOf("api.example.com"))
        val resolved = mutableListOf<Profile>()
        val later = adapter({ answer })
        ProfileClient(
            profile(), catalog, MutableStateFlow(ProvidersSetting(listOf(moved))), { found, _ -> target().also { resolved += found } }, { later },
            { true }, spend = { _, _, _, _ -> }, limits = NoLimits,
        ).complete(ModelRole.LIGHTWEIGHT, request)
        assertEquals(listOf(moved), resolved)
    }

    @Test
    fun theDefaultClientIsNullOnlyWithoutADefaultProfile() {
        val with = MutableStateFlow<ProvidersSetting?>(ProvidersSetting(listOf(profile()), defaultProfileId = "test-profile"))
        val without = MutableStateFlow<ProvidersSetting?>(ProvidersSetting(listOf(profile())))

        assertEquals("test-profile", ProvidersRepository(with, with) { p, l -> client(p, NeverAdapter, limits = l) }.defaultClient()?.profile?.id)
        assertEquals(null, ProvidersRepository(without, without) { p, l -> client(p, NeverAdapter, limits = l) }.defaultClient())
    }

    private object NeverAdapter : ChatAdapter {
        override suspend fun complete(request: ChatRequest, target: ModelTarget): ChatResponse = error("not called")
    }
}
