package dev.kestrel.providers.credentials

import dev.kestrel.providers.catalog.AuthMethod
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.KeyHeader
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.http.RefreshResult
import dev.kestrel.providers.profiles.Profile
import dev.kestrel.providers.profiles.ProfileModel
import dev.kestrel.providers.signin.Refresh
import dev.kestrel.providers.signin.SignInFixtures
import dev.kestrel.providers.signin.SignInFixtures.catalog
import dev.kestrel.providers.signin.TokenRefresher
import dev.kestrel.providers.testing.RedirectingCalls
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** CredentialManager's cache and its refresh rules (design D9 and D15), with a vault and a refresher in memory. */
class CredentialManagerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var now = 1_000_000_000_000L
    private val vault = MemoryVault()
    private val http = ProviderHttp(Call.Factory { _: Request -> error("no request in these tests") })
    private val request = HttpRequest("POST", "https://api.openai.com/v1/responses".toHttpUrl())

    @After
    fun stop() {
        scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
    }

    private class MemoryVault : CredentialVault {
        val stored = ConcurrentHashMap<String, Credential>()
        val reads = AtomicInteger()

        /** When set, a read takes the stored value and then waits for this gate before it returns that value. */
        @Volatile
        var readGate: CompletableDeferred<Unit>? = null

        override suspend fun read(id: String): Credential? {
            val value = stored[id]
            reads.incrementAndGet()
            readGate?.await()
            return value
        }

        override suspend fun write(id: String, credential: Credential) {
            stored[id] = credential
        }

        override suspend fun delete(id: String) {
            stored.remove(id)
        }
    }

    /** Gives [outcome] after [gate] completes, and counts its calls. */
    private class TestRefresher(var outcome: (Credential.OAuthToken) -> Refresh, val gate: CompletableDeferred<Unit>? = null) : TokenRefresher {
        val calls = AtomicInteger()

        override suspend fun refresh(token: Credential.OAuthToken): Refresh {
            calls.incrementAndGet()
            gate?.await()
            return outcome(token)
        }
    }

    private fun manager(refresher: TokenRefresher?) = CredentialManager(vault, http, catalog, scope, { now }) { refresher }

    private fun profile(provider: String = "openai", choice: String = "chatgpt-plan", credential: String? = "test-credential") =
        Profile("test-profile", "Test", provider, choice, AuthMethod.BROWSER, credential, lightweight = ProfileModel("a"), versatile = ProfileModel("b"))

    private fun token(access: String = "test-access-1", expiresIn: Long = 3_600_000, earliest: Long? = null) =
        Credential.OAuthToken(access, "test-refresh-1", now + expiresIn, earliest, clientId = "oaiapp_test")

    private fun rotated(token: Credential.OAuthToken) = Refresh.Done(token.copy(access = "test-access-2", refresh = "test-refresh-2", expiresAtMillis = now + 3_600_000))

    private fun errorOf(block: suspend () -> Unit): ProviderError =
        try {
            runBlocking { block() }
            fail("expected a ProviderException")
            throw AssertionError()
        } catch (e: ProviderException) {
            e.error
        }

    @Test
    fun anApiKeyGoesInItsHeaderAndARefusalIsUnauthorized() = runBlocking {
        vault.stored["test-credential"] = Credential.ApiKey("test-key")
        val auth = manager(null).auth(profile("anthropic", "apiKey"), KeyHeader.X_API_KEY)

        assertEquals(mapOf("x-api-key" to "test-key"), auth.headers(request))
        assertEquals(ProviderError.Unauthorized, auth.rejected)
        assertEquals(RefreshResult.NotPossible, auth.refresh())
    }

    @Test
    fun aGitHubTokenIsTheBearerAndARefusalAsksForANewSignIn() = runBlocking {
        vault.stored["test-credential"] = Credential.GitHubToken("test-github-token", "github.com")
        val auth = manager(null).auth(profile("github-copilot", "github-copilot"), KeyHeader.BEARER)

        assertEquals(mapOf("Authorization" to "Bearer test-github-token"), auth.headers(request))
        assertEquals(ProviderError.SignInExpired("github-copilot"), auth.rejected)
        assertEquals(RefreshResult.NotPossible, auth.refresh())
    }

    @Test
    fun aMissingCredentialFailsEveryRequestAsAnExpiredSignIn() {
        val error = errorOf { manager(null).auth(profile(), KeyHeader.BEARER).headers(request) }

        assertEquals(ProviderError.SignInExpired("openai"), error)
    }

    @Test
    fun aTokenThatExpiresWithinAMinuteIsRefreshedBeforeTheRequestAndStored() = runBlocking {
        vault.stored["test-credential"] = token(expiresIn = 30_000)
        val refresher = TestRefresher(::rotated)
        val auth = manager(refresher).auth(profile(), KeyHeader.BEARER)

        assertEquals(mapOf("Authorization" to "Bearer test-access-2"), auth.headers(request))
        assertEquals("test-refresh-2", (vault.stored["test-credential"] as Credential.OAuthToken).refresh)
        assertEquals(1, refresher.calls.get())
    }

    @Test
    fun theEarlyRefreshWaitsForEarliestRefreshAtButAnExpiredTokenRefreshesAtOnce() = runBlocking {
        vault.stored["test-credential"] = token(expiresIn = 30_000, earliest = now + 45_000)
        val refresher = TestRefresher(::rotated)
        val auth = manager(refresher).auth(profile(), KeyHeader.BEARER)

        assertEquals(mapOf("Authorization" to "Bearer test-access-1"), auth.headers(request))
        assertEquals(0, refresher.calls.get())
        now += 31_000
        // Expired, although earliest_refresh_at is still 14 s away.
        assertEquals(mapOf("Authorization" to "Bearer test-access-2"), auth.headers(request))
        assertEquals(1, refresher.calls.get())
    }

    @Test
    fun refreshesOfOneCredentialRunOneAtATimeAndAWaitingCallerUsesTheNewToken() = runBlocking {
        vault.stored["test-credential"] = token()
        val gate = CompletableDeferred<Unit>()
        val refresher = TestRefresher(::rotated, gate)
        val manager = manager(refresher)
        val first = manager.auth(profile(), KeyHeader.BEARER).also { it.headers(request) }
        val second = manager.auth(profile(), KeyHeader.BEARER).also { it.headers(request) }

        val one = async(Dispatchers.Default) { first.refresh() }
        val two = async(Dispatchers.Default) { second.refresh() }
        withTimeout(5_000) { while (refresher.calls.get() == 0) kotlinx.coroutines.delay(10) }
        gate.complete(Unit)

        assertEquals(RefreshResult.Refreshed, one.await())
        assertEquals(RefreshResult.Refreshed, two.await())
        assertEquals(1, refresher.calls.get())
        assertEquals(mapOf("Authorization" to "Bearer test-access-2"), second.headers(request))
    }

    @Test
    fun aCallerCancelledDuringARefreshStillGetsTheRotatedTokenStored() = runBlocking {
        vault.stored["test-credential"] = token()
        val gate = CompletableDeferred<Unit>()
        val refresher = TestRefresher(::rotated, gate)
        val auth = manager(refresher).auth(profile(), KeyHeader.BEARER).also { it.headers(request) }

        val caller = launch(Dispatchers.Default) { auth.refresh() }
        withTimeout(5_000) { while (refresher.calls.get() == 0) kotlinx.coroutines.delay(10) }
        caller.cancelAndJoin()
        gate.complete(Unit)

        withTimeout(5_000) { while ((vault.stored["test-credential"] as Credential.OAuthToken).refresh != "test-refresh-2") kotlinx.coroutines.delay(10) }
    }

    @Test
    fun aRefreshThatFailsForNowKeepsTheSignInAndAnExpiredTokenFailsWithThatError() = runBlocking {
        vault.stored["test-credential"] = token(expiresIn = 30_000)
        val refresher = TestRefresher({ Refresh.ForNow(ProviderError.RateLimited(5_000)) })
        val manager = manager(refresher)
        val auth = manager.auth(profile(), KeyHeader.BEARER)

        // Still valid for 30 s, so the request goes with the old token.
        assertEquals(mapOf("Authorization" to "Bearer test-access-1"), auth.headers(request))
        assertEquals(RefreshResult.FailedForNow(ProviderError.RateLimited(5_000)), auth.refresh())
        // The early refresh waits for the Retry-After of its failure, so the next request, also with a new RequestAuth as
        // each call resolves one, does not ask again.
        assertEquals(mapOf("Authorization" to "Bearer test-access-1"), manager.auth(profile(), KeyHeader.BEARER).headers(request))
        assertEquals(2, refresher.calls.get())
        now += 31_000
        assertEquals(ProviderError.RateLimited(5_000), errorOf { auth.headers(request) })
        assertEquals("test-access-1", (vault.stored["test-credential"] as Credential.OAuthToken).access)
    }

    @Test
    fun anEarlyRefreshThatTheProviderRefusesWithAnUnlistedErrorKeepsTheValidToken() = runBlocking {
        vault.stored["test-credential"] = token(expiresIn = 30_000)
        val refresher = TestRefresher({ Refresh.ForNow(ProviderError.BadRequest(400, null)) })
        val auth = manager(refresher).auth(profile(), KeyHeader.BEARER)

        assertEquals(mapOf("Authorization" to "Bearer test-access-1"), auth.headers(request))
        assertEquals(1, refresher.calls.get())
    }

    @Test
    fun anExpiredTokenWhoseRefreshTheProviderRefusesWithA4xxFailsAsAnExpiredSignIn() {
        vault.stored["test-credential"] = token(expiresIn = -1_000)
        val auth = runBlocking { manager(TestRefresher({ Refresh.ForNow(ProviderError.BadRequest(403, null)) })).auth(profile(), KeyHeader.BEARER) }

        assertEquals(ProviderError.SignInExpired("openai"), errorOf { auth.headers(request) })
        assertEquals("test-access-1", (vault.stored["test-credential"] as Credential.OAuthToken).access)
    }

    @Test
    fun aRefreshAfterA401ThatTheProviderRefusesWithA4xxEndsTheSignInWhateverTheTokensExpiry() = runBlocking {
        val refused = TestRefresher({ Refresh.ForNow(ProviderError.BadRequest(400, null)) })
        vault.stored["test-credential"] = token()
        val valid = manager(refused).auth(profile(), KeyHeader.BEARER).also { it.headers(request) }
        assertEquals(RefreshResult.Expired, valid.refresh())

        vault.stored["test-credential"] = token().copy(expiresAtMillis = null)
        assertEquals(RefreshResult.Expired, manager(refused).auth(profile(), KeyHeader.BEARER).refresh())
        // A rate limit of the refresh is not a refusal.
        val limited = TestRefresher({ Refresh.ForNow(ProviderError.RateLimited(null)) })
        assertEquals(RefreshResult.FailedForNow(ProviderError.RateLimited(null)), manager(limited).auth(profile(), KeyHeader.BEARER).refresh())
        assertEquals("test-access-1", (vault.stored["test-credential"] as Credential.OAuthToken).access)
    }

    @Test
    fun aRefusedRefreshExpiresTheSignInAndInvalidClientClearsTheClientId() = runBlocking {
        val original = token()
        vault.stored["test-credential"] = original
        val refresher = TestRefresher({ Refresh.Refused(it.copy(clientId = null)) })
        val auth = manager(refresher).auth(profile(), KeyHeader.BEARER).also { it.headers(request) }

        assertEquals(RefreshResult.Expired, auth.refresh())
        assertEquals(ProviderError.SignInExpired("openai"), auth.rejected)
        assertNull((vault.stored["test-credential"] as Credential.OAuthToken).clientId)
    }

    @Test
    fun aStoredCredentialIsReadFromTheVaultOnceAndForgottenWhenDeleted() = runBlocking {
        val manager = manager(null)
        val id = manager.store(Credential.ApiKey("test-key"))

        assertEquals(Credential.ApiKey("test-key"), manager.read(id))
        manager.store(Credential.ApiKey("test-key-2"), id)
        assertEquals(Credential.ApiKey("test-key-2"), manager.read(id))
        assertEquals(0, vault.reads.get())
        manager.delete(id)
        assertNull(manager.read(id))
        assertNull(vault.stored[id])
    }

    @Test
    fun aHeldCredentialAuthenticatesWithoutReachingTheVaultUntilItIsReleased() = runBlocking {
        val manager = manager(null)
        val id = manager.hold(Credential.ApiKey("test-key"))

        val auth = manager.auth(profile("anthropic", "apiKey", credential = id), KeyHeader.X_API_KEY)

        assertEquals(mapOf("x-api-key" to "test-key"), auth.headers(request))
        assertEquals(emptyMap<String, Credential>(), vault.stored.toMap())
        manager.release(id)
        assertNull(manager.read(id))
    }

    @Test
    fun aHeldTokenIsNeitherRefreshedNorStored() = runBlocking {
        val refresher = TestRefresher(::rotated)
        val manager = manager(refresher)
        val id = manager.hold(token(expiresIn = 30_000))
        val auth = manager.auth(profile(credential = id), KeyHeader.BEARER)

        assertEquals(mapOf("Authorization" to "Bearer test-access-1"), auth.headers(request))
        assertEquals(RefreshResult.NotPossible, auth.refresh())
        assertEquals(0, refresher.calls.get())
        assertEquals(emptyMap<String, Credential>(), vault.stored.toMap())
    }

    @Test
    fun aDeleteDuringTheFirstReadFromTheVaultIsNotUndoneInMemory() = runBlocking {
        vault.stored["test-credential"] = token()
        val gate = CompletableDeferred<Unit>()
        vault.readGate = gate
        val manager = manager(null)
        val first = scope.async { manager.read("test-credential") }
        withTimeout(5_000) { while (vault.reads.get() == 0) delay(1) }

        val deleted = scope.launch { manager.delete("test-credential") }
        // The delete waits for the read's lock; without that lock it would end here, before the read puts its value in memory.
        withTimeoutOrNull(500) { deleted.join() }
        gate.complete(Unit)
        first.await()
        withTimeout(5_000) { deleted.join() }
        vault.readGate = null

        assertNull(manager.read("test-credential"))
        assertEquals(emptyMap<String, Credential>(), vault.stored.toMap())
    }

    @Test
    fun aServiceAccountsTokenIsFetchedOnceForSeveralRequests() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keys.private.encoded) +
                "\n-----END PRIVATE KEY-----\n"
            // An account of its own, because plan 4B's token cache is shared by the whole process.
            vault.stored["test-credential"] = Credential.ServiceAccountKey("test-manager@test-project.iam.gserviceaccount.com", pem, null, "test-project")
            repeat(2) { server.enqueue(SignInFixtures.json(200, """{"access_token":"test-vertex-token","expires_in":3600,"token_type":"Bearer"}""")) }
            val manager = CredentialManager(vault, ProviderHttp(RedirectingCalls(server)), catalog, scope, { now }) { null }

            repeat(2) { manager.auth(profile("google-vertex", "vertex-service-account"), KeyHeader.BEARER).headers(request) }

            assertEquals(1, server.requestCount)
        } finally {
            server.close()
        }
    }
}
