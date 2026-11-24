package dev.kestrel.app.providers

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.KestrelApplication
import dev.kestrel.privacy.logs.NetworkEvent
import dev.kestrel.privacy.network.BlockedRequestException
import dev.kestrel.privacy.network.NetworkGateway
import dev.kestrel.privacy.network.NetworkPolicy
import dev.kestrel.providers.catalog.AuthMethod
import dev.kestrel.providers.catalog.LocalServerKind
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.chat.Part
import dev.kestrel.providers.client.PlanLimits
import dev.kestrel.providers.client.ProfileClient
import dev.kestrel.providers.client.TargetResolver
import dev.kestrel.providers.cost.Price
import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.credentials.CredentialManager
import dev.kestrel.providers.credentials.CredentialVault
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.http.RefreshResult
import dev.kestrel.providers.http.RequestAuth
import dev.kestrel.providers.profiles.ModelRole
import dev.kestrel.providers.profiles.Profile
import dev.kestrel.providers.profiles.ProfileHosts
import dev.kestrel.providers.profiles.ProfileModel
import dev.kestrel.providers.profiles.ProvidersSetting
import dev.kestrel.providers.styles.Adapters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A provider request on the device through the real network gateway (design D19): a profile with test-only names for a
 * "local server" at a MockWebServer on the device, its allowlist, its network log without bodies, a host off the list,
 * its price and the month's spending, and a 401 handled by the rule of one refresh. It builds its own gateway, allowlist
 * and setting, so the owner's profiles, credentials and spending are never touched.
 */
@RunWith(AndroidJUnit4::class)
class ProviderEndToEndTest {
    private val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KestrelApplication).graph
    private val server = MockWebServer()
    private val events = CopyOnWriteArrayList<NetworkEvent>()
    private val policy = NetworkPolicy()
    private val gateway = NetworkGateway(policy) { events += it }
    private val http = ProviderHttp(gateway.callFactory, classify = { if (it is BlockedRequestException) ProviderError.NotAllowed else null })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val spent = CopyOnWriteArrayList<Double>()
    private val secret = "test-secret-prompt"
    private val request = ChatRequest("You are a test.", listOf(ChatMessage.User(listOf(Part.Text(secret)))), maxOutputTokens = 16)

    private val vault = object : CredentialVault {
        override suspend fun read(id: String): Credential? = null

        override suspend fun write(id: String, credential: Credential) = Unit

        override suspend fun delete(id: String) = Unit

        override suspend fun contains(id: String): Boolean = false
    }

    private object NoLimits : PlanLimits {
        override fun reached(profileId: String) = Unit

        override fun cleared(profileId: String) = Unit
    }

    @Before
    fun start() {
        server.start()
    }

    @After
    fun stop() {
        server.close()
        scope.cancel()
    }

    private fun profile(base: String) = Profile(
        "test-local", "Test", "lmstudio", "lmstudio-server", AuthMethod.SERVER_ADDRESS, null, baseUrl = base, localServer = LocalServerKind.LM_STUDIO,
        lightweight = ProfileModel("test-model", price = Price(input = 1.0, output = 2.0)), versatile = ProfileModel("test-model"),
    )

    private fun client(profile: Profile): ProfileClient {
        val resolver = TargetResolver(graph.catalog, CredentialManager(vault, http, graph.catalog, scope))
        return ProfileClient(
            profile, graph.catalog, MutableStateFlow(ProvidersSetting(listOf(profile))), resolver::resolve, { Adapters.forStyle(it, http) },
            { true }, spend = { _, _, credits, _ -> spent += credits }, limits = NoLimits,
        )
    }

    @Test
    fun aRequestToAnAllowedLocalServerIsPricedAndLoggedWithoutItsBody() = runBlocking {
        val profile = profile(server.url("/v1").toString())
        policy.setLocalServers(ProfileHosts.localServers(profile))
        server.enqueue(
            MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream").body(
                "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"OK\"},\"finish_reason\":null}]}\n\n" +
                    "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1000,\"completion_tokens\":500,\"total_tokens\":1500}}\n\n" +
                    "data: [DONE]\n\n",
            ).build(),
        )

        val priced = client(profile).complete(ModelRole.LIGHTWEIGHT, request)

        assertTrue(priced.cost.credits > 0)
        assertEquals(listOf(priced.cost.credits), spent.toList())
        assertTrue(server.takeRequest().body!!.utf8().contains(secret))
        val logged = events.single()
        assertEquals("POST", logged.method)
        assertEquals(server.url("/").host, logged.host)
        assertEquals("/v1/chat/completions", logged.path)
        assertEquals(200, logged.status)
        assertTrue(events.none { secret in it.toString() })
    }

    @Test
    fun aHostOffTheAllowlistIsRefusedBeforeAnyLookup() = runBlocking {
        val profile = profile("https://not-allowed.invalid/v1")

        try {
            client(profile).complete(ModelRole.LIGHTWEIGHT, request)
            fail("expected a ProviderException")
        } catch (e: ProviderException) {
            assertEquals(ProviderError.NotAllowed, e.error)
        }
        assertEquals("not-allowed.invalid", events.single().host)
        assertTrue(events.single().detail.orEmpty().contains("not on the allowlist"))
    }

    @Test
    fun a401IsRefreshedOnceAndSentAgain() = runBlocking {
        policy.setLocalServers(setOf(server.url("/").host))
        server.enqueue(MockResponse.Builder().code(401).build())
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())
        val auth = object : RequestAuth {
            var token = "test-token-1"
            var refreshes = 0

            override suspend fun headers(request: HttpRequest) = mapOf("Authorization" to "Bearer $token")

            override suspend fun refresh(): RefreshResult {
                refreshes++
                token = "test-token-2"
                return RefreshResult.Refreshed
            }
        }

        val reply = http.send(HttpRequest("GET", server.url("/v1/models")), auth)

        assertEquals(200, reply.status)
        assertEquals(1, auth.refreshes)
        assertEquals("Bearer test-token-1", server.takeRequest().headers["Authorization"])
        assertEquals("Bearer test-token-2", server.takeRequest().headers["Authorization"])
        assertEquals(listOf(401, 200), events.map { it.status })
    }
}
