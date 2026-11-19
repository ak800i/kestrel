package dev.kestrel.app.providers

import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.KestrelApplication
import dev.kestrel.app.SettingKeys
import dev.kestrel.app.settings.StoredProviderData
import dev.kestrel.privacy.crypto.AesGcmFieldCipher
import dev.kestrel.privacy.db.SettingRow
import dev.kestrel.privacy.db.SettingsDao
import dev.kestrel.privacy.network.NetworkGateway
import dev.kestrel.privacy.network.NetworkPolicy
import dev.kestrel.privacy.network.PolicyDecision
import dev.kestrel.privacy.settings.SettingsStore
import dev.kestrel.providers.catalog.AuthMethod
import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.credentials.CredentialVault
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.profiles.Profile
import dev.kestrel.providers.profiles.ProfileModel
import dev.kestrel.providers.profiles.ProvidersSetting
import dev.kestrel.providers.signin.LoopbackAddresses
import dev.kestrel.providers.signin.LoopbackCallback
import dev.kestrel.providers.signin.LoopbackListener
import dev.kestrel.providers.signin.LoopbackSession
import dev.kestrel.providers.signin.SignInFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator

/**
 * ProviderGraph's saves and sign-ins on the device (design D9, D10 and D15), with a graph of its own: settings in memory,
 * credentials in memory, a fake loopback listener, and OpenRouter's key exchange answered by a MockWebServer on the
 * device, so that no request leaves the device and the owner's profiles and credentials are never touched. SignInService
 * is not started: the graph's context ignores service starts.
 */
@RunWith(AndroidJUnit4::class)
class ProviderGraphTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val catalog = (target.applicationContext as KestrelApplication).graph.catalog
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao = MemoryDao()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val settings = SettingsStore(dao, AesGcmFieldCipher { key })
    private val vault = MemoryVault()
    private val loopback = FakeLoopback()

    /** The graph's allowlist, where its sign-ins and checks open their temporary leases. */
    private val leases = NetworkPolicy()
    /** Whether the context refuses to start a foreground service, as Android does while Kestrel is in the background. */
    @Volatile
    private var refuseServiceStarts = false

    private val context = object : ContextWrapper(target) {
        override fun startForegroundService(service: Intent): ComponentName? {
            if (refuseServiceStarts) throw IllegalStateException("test: Android refused the start")
            return null
        }

        override fun startService(service: Intent): ComponentName? = null
    }

    private lateinit var graph: ProviderGraph

    /** For each use of the HTTP client and each call: whether it ran on the main thread. */
    private val onMain = ConcurrentLinkedQueue<Boolean>()

    @Before
    fun start() {
        server.start()
        val policy = NetworkPolicy().apply { setLocalServers(setOf(server.url("/").host)) }
        val gateway = NetworkGateway(policy) {}
        // Every request goes to the MockWebServer, with its path, as plan 4A's RedirectingCalls does in the JVM tests.
        val calls = object : Call.Factory {
            override fun newCall(request: Request): Call {
                onMain += Looper.myLooper() == Looper.getMainLooper()
                return gateway.callFactory.newCall(request.newBuilder().url(server.url(request.url.encodedPath)).build())
            }
        }
        graph = ProviderGraph(
            context, scope, settings, { catalog },
            {
                onMain += Looper.myLooper() == Looper.getMainLooper()
                ProviderHttp(calls)
            },
            vault, MutableStateFlow(null), leases, { _, _ -> }, loopback, { true },
        )
    }

    @After
    fun stop() {
        loopback.release()
        scope.cancel()
        server.close()
    }

    private fun draft() = Profile(
        "test-profile", "Test", "openrouter", "openrouter-api-key", AuthMethod.API_KEY, null,
        lightweight = ProfileModel("test-model"), versatile = ProfileModel("test-model"),
    )

    private fun copilot(choiceId: String = "github-copilot", credentialId: String? = "test-credential") = Profile(
        "test-copilot", "Copilot", "github-copilot", choiceId, AuthMethod.DEVICE_CODE, credentialId,
        baseUrl = "https://api.individual.githubcopilot.com", lightweight = ProfileModel("test-model"), versatile = ProfileModel("test-model"),
    )

    /** The error of [block]'s ProviderException, or null when it ended without one; a request that hangs fails after 5 s. */
    private suspend fun errorOf(block: suspend () -> Unit): ProviderError? =
        try {
            withTimeout(5_000) { block() }
            null
        } catch (e: ProviderException) {
            e.error
        }

    @Test
    fun aSecondSaveOfTheSameProfileLeavesOneCredential() = runBlocking {
        graph.save(draft(), Credential.ApiKey("sk-test-first"), emptyList(), makeDefault = false)
        val saved = graph.save(draft(), Credential.ApiKey("sk-test-second"), emptyList(), makeDefault = false)

        assertEquals(setOf(saved.credentialId), vault.ids())
        assertEquals(saved.credentialId, settings.get(SettingKeys.PROVIDERS).profiles.single().credentialId)
    }

    @Test
    fun aSaveThatFailsBeforeTheProfileIsStoredDeletesItsCredential() = runBlocking {
        dao.failing = SettingKeys.PROVIDERS.name

        try {
            graph.save(draft(), Credential.ApiKey("sk-test-first"), emptyList(), makeDefault = false)
            fail("expected the save to fail")
        } catch (e: IOException) {
            // The store's failure, as a full disk.
        }

        assertEquals(emptySet<String>(), vault.ids())
    }

    @Test
    fun aSignInAgainStoresItsResultInTheProfileWithoutItsPage() = runBlocking {
        val profile = draft().copy(choiceId = "openrouter-oauth", method = AuthMethod.BROWSER, credentialId = "test-credential")
        vault.write("test-credential", Credential.ApiKey("sk-test-old"))
        settings.set(SettingKeys.PROVIDERS, ProvidersSetting(listOf(profile), defaultProfileId = profile.id))
        server.enqueue(MockResponse.Builder().code(200).body("""{"key": "sk-test-new"}""").build())
        loopback.hold()
        withTimeout(10_000) { graph.stored.first { it?.profiles?.isNotEmpty() == true } }

        graph.signInAgain(profile.id)
        assertTrue("the loopback opened", loopback.opened.await(10, TimeUnit.SECONDS))
        // As a request for another page while the profile's sign-in runs: it is not the add flow's, so it goes on.
        graph.leaveAddFlow()
        loopback.release()
        val done = withTimeout(10_000) { graph.signIn.first { it !is SignInState.Waiting } }

        assertTrue("the sign-in succeeded: $done", done is SignInState.Succeeded && done.profileId == profile.id)
        assertEquals(Credential.ApiKey("sk-test-new"), vault.read("test-credential"))
        assertNotNull(settings.get(SettingKeys.PROVIDERS).profiles.single().signedInAt)
        graph.forgetRenewal()
        assertEquals(SignInState.Idle, graph.signIn.value)
    }

    @Test
    fun aProfileDeletedWhileItsSignInAgainRunsKeepsNoCredential() = runBlocking {
        val profile = draft().copy(choiceId = "openrouter-oauth", method = AuthMethod.BROWSER, credentialId = "test-credential")
        vault.write("test-credential", Credential.ApiKey("sk-test-old"))
        settings.set(SettingKeys.PROVIDERS, ProvidersSetting(listOf(profile), defaultProfileId = profile.id))
        server.enqueue(MockResponse.Builder().code(200).body("""{"key": "sk-test-new"}""").build())
        loopback.hold()
        withTimeout(10_000) { graph.stored.first { it?.profiles?.isNotEmpty() == true } }
        graph.signInAgain(profile.id)
        assertTrue("the loopback opened", loopback.opened.await(10, TimeUnit.SECONDS))

        // The deletion stops after it deleted the credential and before it removed the profile, and the sign-in ends then.
        vault.deleteGate = CountDownLatch(1)
        val deletion = scope.async { graph.delete(profile.id) }
        assertTrue("the deletion reached the vault", vault.deleted.await(10, TimeUnit.SECONDS))
        loopback.release()
        val ended = withTimeout(10_000) { graph.signIn.first { it !is SignInState.Waiting } }
        vault.deleteGate.countDown()
        withTimeout(10_000) { deletion.await() }

        assertEquals(SignInState.Idle, ended)
        assertEquals(emptySet<String>(), vault.ids())
        assertEquals(emptyList<Profile>(), settings.get(SettingKeys.PROVIDERS).profiles)
    }

    @Test
    fun aSignInAgainWhoseCredentialCannotBeStoredSaysSo() = runBlocking {
        val profile = draft().copy(choiceId = "openrouter-oauth", method = AuthMethod.BROWSER, credentialId = "test-credential")
        vault.write("test-credential", Credential.ApiKey("sk-test-old"))
        settings.set(SettingKeys.PROVIDERS, ProvidersSetting(listOf(profile), defaultProfileId = profile.id))
        server.enqueue(MockResponse.Builder().code(200).body("""{"key": "sk-test-new"}""").build())
        withTimeout(10_000) { graph.stored.first { it?.profiles?.isNotEmpty() == true } }
        vault.failingWrites = true

        graph.signInAgain(profile.id)
        val done = withTimeout(10_000) { graph.signIn.first { it !is SignInState.Waiting } }

        assertEquals(SignInState.Failed("openrouter", "openrouter-oauth", SignInFailure.NOT_STORED, null, profile.id), done)
        assertEquals(Credential.ApiKey("sk-test-old"), vault.read("test-credential"))
    }

    @Test
    fun thePagesCallsBuildAndUseTheHttpClientOffTheMainThread() = runBlocking {
        val copilot = copilot()
        vault.write("test-credential", Credential.GitHubToken("test-github-token", "github.com"))
        settings.set(SettingKeys.PROVIDERS, ProvidersSetting(listOf(copilot), defaultProfileId = copilot.id))
        server.enqueue(MockResponse.Builder().code(200).body("""{"data": []}""").build())
        withTimeout(10_000) { graph.stored.first { it?.profiles?.isNotEmpty() == true } }

        // As the page calls it, from the main thread.
        withContext(Dispatchers.Main) { StoredProviderData(graph).refreshModels(copilot.id) }

        assertEquals("the client was built and one request sent", 2, onMain.size)
        assertEquals(listOf(false, false), onMain.toList())
    }

    @Test
    fun aProfileDeletedWhileItsSignInAgainStoresTheNewCredentialKeepsNoCredential() = runBlocking {
        // Without a credential ID, the store takes a new one, so only the graph's profileChanges orders the store with the
        // deletion: the credential's own lock already orders a store and a delete of the same ID.
        val profile = draft().copy(choiceId = "openrouter-oauth", method = AuthMethod.BROWSER, credentialId = null)
        settings.set(SettingKeys.PROVIDERS, ProvidersSetting(listOf(profile), defaultProfileId = profile.id))
        server.enqueue(MockResponse.Builder().code(200).body("""{"key": "sk-test-new"}""").build())
        withTimeout(10_000) { graph.stored.first { it?.profiles?.isNotEmpty() == true } }
        // The store's vault write waits until the deletion has run, or for 2 s when the deletion waits for the store.
        val deleted = CountDownLatch(1)
        vault.writeGate = deleted

        graph.signInAgain(profile.id)
        assertTrue("the store reached the vault", vault.writing.await(10, TimeUnit.SECONDS))
        val deletion = scope.async { graph.delete(profile.id).also { deleted.countDown() } }
        withTimeout(10_000) { deletion.await() }
        assertTrue("the store wrote the vault", vault.written.await(10, TimeUnit.SECONDS))

        assertEquals(emptySet<String>(), vault.ids())
        assertEquals(emptyList<Profile>(), settings.get(SettingKeys.PROVIDERS).profiles)
    }

    @Test
    fun aSwitchedOffBorrowedSignInIsRefusedBeforeAnyLeaseOrRequest() = runBlocking {
        val profile = copilot()
        vault.write("test-credential", Credential.GitHubToken("test-github-token", "github.com"))
        settings.set(SettingKeys.PROVIDERS, ProvidersSetting(listOf(profile), defaultProfileId = profile.id, signInSwitches = mapOf("copilot" to false)))
        withTimeout(10_000) { graph.stored.first { it?.profiles?.isNotEmpty() == true } }
        val switchedOff = ProviderError.SignInSwitchedOff("github-copilot")

        assertEquals(switchedOff, errorOf { graph.refreshModels(profile.id) })
        assertNoCopilotLease()
        assertEquals(switchedOff, errorOf { graph.signInAgain(profile.id) })
        assertNoCopilotLease()
        assertEquals(switchedOff, errorOf { graph.check(profile.copy(id = "test-draft", credentialId = null), Credential.GitHubToken("test-github-token", "github.com")) })
        assertNoCopilotLease()
        graph.startSignIn("github-copilot", "github-copilot")

        assertEquals(SignInState.Failed("github-copilot", "github-copilot", null, switchedOff), graph.signIn.value)
        assertNoCopilotLease()
        assertEquals(0, server.requestCount)
    }

    @Test
    fun switchingABorrowedSignInOffEndsASignInThatRunsWithIt() = runBlocking {
        val code = """{"device_code": "test-device", "user_code": "TEST-CODE", "verification_uri": "https://github.com/login/device", "expires_in": 900, "interval": 5}"""
        server.enqueue(MockResponse.Builder().code(200).body(code).build())
        graph.startSignIn("github-copilot", "github-copilot")
        val shown = withTimeout(10_000) { graph.signIn.first { it !is SignInState.Waiting || it.prompt != null } }
        assertTrue("the sign-in shows its code: $shown", shown is SignInState.Waiting)
        assertTrue("the sign-in leased github.com", leases.check("https://github.com/".toHttpUrl()) is PolicyDecision.Allowed)

        graph.setSwitch("copilot", false)

        assertEquals(SignInState.Failed("github-copilot", "github-copilot", null, ProviderError.SignInSwitchedOff("github-copilot")), graph.signIn.value)
        withTimeout(5_000) { while (leases.check("https://github.com/".toHttpUrl()) !is PolicyDecision.Blocked) delay(50) }
        // The flow would poll 5 s after the code; it no longer runs, so only the code's request reached the server.
        delay(6_000)
        assertEquals(1, server.requestCount)
    }

    /** That no lease of the graph lets a Copilot host through. */
    private fun assertNoCopilotLease() {
        for (host in listOf("github.com", "api.github.com", "api.individual.githubcopilot.com")) {
            assertTrue("$host is leased", leases.check("https://$host/".toHttpUrl()) is PolicyDecision.Blocked)
        }
    }

    @Test
    fun aTenantsSignInAgainWithoutItsTokenSignsInOnTheTenantThatTheProfileNames() = runBlocking {
        val profile = copilot("github-copilot-enterprise", "test-missing").copy(
            baseUrl = "https://copilot-api.octo.ghe.com",
            discoveredHosts = listOf("api.octo.ghe.com", "copilot-api.octo.ghe.com"),
        )
        settings.set(SettingKeys.PROVIDERS, ProvidersSetting(listOf(profile), defaultProfileId = profile.id))
        val code = """{"device_code": "test-device", "user_code": "TEST-CODE", "verification_uri": "https://octo.ghe.com/login/device", "expires_in": 900, "interval": 5}"""
        server.enqueue(MockResponse.Builder().code(200).body(code).build())
        withTimeout(10_000) { graph.stored.first { it?.profiles?.isNotEmpty() == true } }

        graph.signInAgain(profile.id)
        val shown = withTimeout(10_000) { graph.signIn.first { it !is SignInState.Waiting || it.prompt != null } }
        graph.cancelSignIn()

        assertTrue("the sign-in shows its code: $shown", shown is SignInState.Waiting && shown.prompt?.verificationUrl == "https://octo.ghe.com/login/device")
        assertEquals("/login/device/code", server.takeRequest().url.encodedPath)
    }

    @Test
    fun aSignInThatEndsBeforeItsPageOpenedNeverOpensItLater() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("""{"key": "sk-test-new"}""").build())

        // As when the Providers page was not shown while the sign-in published its page.
        graph.startSignIn("openrouter", "openrouter-oauth")
        val done = withTimeout(10_000) { graph.signIn.first { it !is SignInState.Waiting } }
        graph.reopenPage()

        assertTrue("the sign-in succeeded: $done", done is SignInState.Succeeded)
        assertNull(graph.browserPage.value)
    }

    @Test
    fun aSignInWhoseServiceAndroidRefusesToStartEndsAtOnce() = runBlocking {
        refuseServiceStarts = true

        graph.startSignIn("openrouter", "openrouter-oauth")

        assertEquals(SignInState.Failed("openrouter", "openrouter-oauth", SignInFailure.EXCHANGE_FAILED, null), graph.signIn.value)
        assertEquals("the sign-in's flow never ran", 1L, loopback.opened.count)
    }

    @Test
    fun aCancelledSignInNeverPublishesItsPage() = runBlocking {
        loopback.hold()

        graph.startSignIn("openrouter", "openrouter-oauth")
        assertTrue("the loopback opened", loopback.opened.await(10, TimeUnit.SECONDS))
        graph.cancelSignIn()
        // The cancelled job is still inside the listener's blocking open, and goes on to its page when that returns.
        loopback.release()
        assertTrue("the cancelled flow reached its wait", loopback.awaited.await(10, TimeUnit.SECONDS))

        assertNull(graph.browserPage.value)
        assertEquals(SignInState.Idle, graph.signIn.value)
    }

    /** The settings table in memory; a write of [failing] throws, as a full disk would. */
    private class MemoryDao : SettingsDao {
        private val rows = MutableStateFlow<Map<String, SettingRow>>(emptyMap())

        @Volatile
        var failing: String? = null

        override suspend fun get(name: String): SettingRow? = rows.value[name]

        override fun observe(name: String): Flow<SettingRow?> = rows.map { it[name] }

        override suspend fun put(row: SettingRow) {
            if (row.name == failing) throw IOException("test: the write failed")
            rows.value = rows.value + (row.name to row)
        }

        override suspend fun delete(name: String): Int {
            val had = name in rows.value
            rows.value = rows.value - name
            return if (had) 1 else 0
        }
    }

    private class MemoryVault : CredentialVault {
        private val stored = ConcurrentHashMap<String, Credential>()

        /** Whether a write throws, as a Keystore failure would. */
        @Volatile
        var failingWrites = false

        /** A delete removes its credential, then waits until this opens; [deleted] opens when one reached it. */
        @Volatile
        var deleteGate = CountDownLatch(0)
        val deleted = CountDownLatch(1)

        /** A write opens [writing], waits until this opens or for 2 s, stores its credential and opens [written]. */
        @Volatile
        var writeGate = CountDownLatch(0)
        val writing = CountDownLatch(1)
        val written = CountDownLatch(1)

        fun ids(): Set<String> = stored.keys.toSet()

        override suspend fun read(id: String): Credential? = stored[id]

        override suspend fun write(id: String, credential: Credential) {
            if (failingWrites) throw IllegalStateException("test: the Keystore failed")
            writing.countDown()
            writeGate.await(2, TimeUnit.SECONDS)
            stored[id] = credential
            written.countDown()
        }

        override suspend fun delete(id: String) {
            stored.remove(id)
            deleted.countDown()
            deleteGate.await(10, TimeUnit.SECONDS)
        }
    }

    /**
     * A loopback listener whose open can block, without a check of cancellation, until [release]; its session answers at
     * once with a code for the expected state.
     */
    private class FakeLoopback : LoopbackListener {
        @Volatile
        private var gate = CountDownLatch(0)
        val opened = CountDownLatch(1)
        val awaited = CountDownLatch(1)

        fun hold() {
            gate = CountDownLatch(1)
        }

        fun release() {
            gate.countDown()
        }

        override suspend fun open(addresses: LoopbackAddresses, port: Int?, path: String): LoopbackSession {
            opened.countDown()
            gate.await(10, TimeUnit.SECONDS)
            return object : LoopbackSession {
                override val port: Int = port ?: 3000

                override suspend fun await(expectedState: String, timeoutMillis: Long): LoopbackCallback {
                    awaited.countDown()
                    return LoopbackCallback(mapOf("code" to "test-code", "state" to expectedState))
                }

                override fun close() = Unit
            }
        }
    }
}
