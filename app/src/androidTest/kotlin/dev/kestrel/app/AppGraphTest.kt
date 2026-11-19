package dev.kestrel.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.privacy.logs.NetworkOutcome
import dev.kestrel.privacy.network.BlockedRequestException
import dev.kestrel.privacy.network.PolicyDecision
import dev.kestrel.privacy.settings.SettingKey
import dev.kestrel.providers.catalog.AuthMethod
import dev.kestrel.providers.catalog.LocalServerKind
import dev.kestrel.providers.profiles.Profile
import dev.kestrel.providers.profiles.ProfileModel
import dev.kestrel.providers.profiles.ProvidersSetting
import dev.kestrel.safety.SafetySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

@RunWith(AndroidJUnit4::class)
class AppGraphTest {
    private val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KestrelApplication).graph

    @Test
    fun blockedRequestIsWrittenToTheEncryptedNetworkLog() = runBlocking {
        graph.logs.deleteHistory()
        val request = Request.Builder().url("https://tracker.example.net/collect").build()

        assertThrows(BlockedRequestException::class.java) { graph.network.callFactory.newCall(request).execute() }

        val entry = withTimeout(5_000) {
            var entries = graph.logs.network()
            while (entries.isEmpty()) {
                delay(50)
                entries = graph.logs.network()
            }
            entries.single()
        }
        assertEquals(NetworkOutcome.BLOCKED, entry.event.outcome)
        assertEquals("tracker.example.net", entry.event.host)
    }

    @Test
    fun plainHttpRequestReachesALoopbackServerAndIsLogged() = runBlocking {
        requestLocalServerOverPlainHttp("127.0.0.1")
    }

    @Test
    fun cleartextPolicyAllowsPlainHttpToALocalNetworkAddress() = runBlocking {
        // Android 17 allows plain HTTP to loopback by default, but not to local-network addresses, so this
        // test needs the network security config. The server listens on the device's own address, so the
        // request never leaves the device. Servers on other machines also need the runtime permission
        // ACCESS_LOCAL_NETWORK (design spec, section 13.6), which Kestrel does not request yet.
        val address = NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
        assumeTrue("The device has no local-network IPv4 address", address != null)
        requestLocalServerOverPlainHttp(address!!)
    }

    private suspend fun requestLocalServerOverPlainHttp(host: String) {
        graph.logs.deleteHistory()
        val server = MockWebServer()
        server.start(InetAddress.getByName(host), 0)
        try {
            // A lease, because AppGraph's allowlist job replaces the local servers with those of the provider profiles at
            // start and after each change of the profiles (design D11 and D16 of sub-project 4).
            graph.networkPolicy.openTemporaryLocalServers(listOf(host)).use {
                server.enqueue(MockResponse.Builder().body("hello").build())
                val request = Request.Builder().url("http://$host:${server.port}/v1/models?key=secret").build()

                val response = graph.network.callFactory.newCall(request).execute()

                assertEquals(200 to "hello", response.use { it.code to it.body.string() })
            }
            val entry = withTimeout(5_000) {
                var local = graph.logs.network().firstOrNull { it.event.host == host }
                while (local == null) {
                    delay(50)
                    local = graph.logs.network().firstOrNull { it.event.host == host }
                }
                local
            }
            assertEquals(NetworkOutcome.COMPLETED, entry.event.outcome)
            assertEquals(200, entry.event.status)
            assertEquals("/v1/models", entry.event.path)
        } finally {
            server.close()
        }
    }

    /** A profile with its own public address on a test host; no request is sent to it. */
    private fun proxy(id: String, host: String) = Profile(
        id = id, name = "Test", providerId = "litellm", choiceId = "litellm-server", method = AuthMethod.SERVER_ADDRESS,
        credentialId = null, baseUrl = "https://$host/v1", lightweight = ProfileModel("a"), versatile = ProfileModel("b"),
    )

    private val lan = proxy("test-allowlist-lan", "unused.test").copy(
        providerId = "ollama",
        choiceId = "ollama-server",
        baseUrl = "http://192.168.77.5:11434",
        localServer = LocalServerKind.OLLAMA,
    )

    private val first = "https://allowlist-one.kestrel.test/v1/chat/completions".toHttpUrl()
    private val second = "https://allowlist-two.kestrel.test/v1/chat/completions".toHttpUrl()
    private val local = "http://192.168.77.5:11434/api/chat".toHttpUrl()

    private fun decisions() = listOf(first, second, local).map { graph.networkPolicy.check(it) }

    private suspend fun awaitApplied(setting: ProvidersSetting) {
        withTimeout(5_000) { graph.appliedProviders.first { it == setting } }
    }

    /**
     * Changes the stored provider setting for [block] with test profiles only, and puts the owner's setting back afterwards
     * (design D15 of sub-project 4).
     */
    private fun withProviderSetting(block: suspend CoroutineScope.(ProvidersSetting) -> Unit) = runBlocking {
        val original = graph.settings.get(SettingKeys.PROVIDERS)
        try {
            block(original)
        } finally {
            graph.settings.set(SettingKeys.PROVIDERS, original)
            awaitApplied(original)
        }
    }

    @Test
    fun aChangedProviderSettingIsOnTheAllowlistBeforeItIsPublished() = withProviderSetting { original ->
        val added = original.copy(profiles = original.profiles + proxy("test-allowlist-1", "allowlist-one.kestrel.test"))
        val changed = original.copy(profiles = original.profiles + proxy("test-allowlist-1", "allowlist-two.kestrel.test") + lan)
        // Unconfined, so that the check runs inside the publishing call, before the job's next line.
        val seen = Collections.synchronizedMap(mutableMapOf<ProvidersSetting, List<PolicyDecision>>())
        val watcher = launch(Dispatchers.Unconfined) {
            graph.appliedProviders.collect { if (it == added || it == changed) seen.putIfAbsent(it, decisions()) }
        }
        try {
            graph.settings.set(SettingKeys.PROVIDERS, added)
            awaitApplied(added)
            graph.settings.set(SettingKeys.PROVIDERS, changed)
            awaitApplied(changed)
        } finally {
            watcher.cancel()
        }

        val allowed = PolicyDecision.Allowed
        assertEquals(
            listOf(
                allowed,
                PolicyDecision.Blocked("allowlist-two.kestrel.test is not on the allowlist"),
                PolicyDecision.Blocked("192.168.77.5 is not on the allowlist"),
            ),
            seen[added],
        )
        assertEquals(listOf(PolicyDecision.Blocked("allowlist-one.kestrel.test is not on the allowlist"), allowed, allowed), seen[changed])
    }

    @Test
    fun aRemovedProfileTakesItsHostsOffTheAllowlist() = withProviderSetting { original ->
        val added = original.copy(profiles = original.profiles + proxy("test-allowlist-1", "allowlist-one.kestrel.test") + lan)
        graph.settings.set(SettingKeys.PROVIDERS, added)
        awaitApplied(added)
        assertEquals(
            listOf(PolicyDecision.Allowed, PolicyDecision.Blocked("allowlist-two.kestrel.test is not on the allowlist"), PolicyDecision.Allowed),
            decisions(),
        )

        graph.settings.set(SettingKeys.PROVIDERS, original)
        awaitApplied(original)

        assertEquals(
            listOf(
                PolicyDecision.Blocked("allowlist-one.kestrel.test is not on the allowlist"),
                PolicyDecision.Blocked("allowlist-two.kestrel.test is not on the allowlist"),
                PolicyDecision.Blocked("192.168.77.5 is not on the allowlist"),
            ),
            decisions(),
        )
    }

    @Test
    fun settingsSurviveTheEncryptedStore() = runBlocking {
        // A test-only key, so that the real safety settings keep test mode on during all testing (D25).
        val key = SettingKey("test.safety", SafetySettings.serializer(), SafetySettings())

        graph.settings.set(key, SafetySettings(testMode = false))

        assertEquals(SafetySettings(testMode = false), graph.settings.get(key))
    }
}
