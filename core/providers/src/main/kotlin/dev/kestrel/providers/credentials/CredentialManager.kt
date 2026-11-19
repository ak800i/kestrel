package dev.kestrel.providers.credentials

import dev.kestrel.providers.catalog.Catalog
import dev.kestrel.providers.http.ApiKeyAuth
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.KeyHeader
import dev.kestrel.providers.http.NoAuth
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.http.RefreshResult
import dev.kestrel.providers.http.RequestAuth
import dev.kestrel.providers.profiles.Profile
import dev.kestrel.providers.signin.Refresh
import dev.kestrel.providers.signin.SignInRegistry
import dev.kestrel.providers.signin.TokenRefresher
import dev.kestrel.providers.styles.ServiceAccountAuth
import dev.kestrel.providers.styles.SigV4Auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The credentials of the profiles (design D9 and D15): it keeps each decrypted credential in memory for the life of the
 * process, because StrongBox is slow, and gives each profile its [RequestAuth].
 *
 * An OAuth token is refreshed before a request when it expires within 60 s (for the ChatGPT plan not before its
 * `earliest_refresh_at`), at once when it has expired, and when a request fails with 401. Refreshes of one credential run
 * one at a time, and a request that waited for one uses the newer token instead of refreshing again. Each refresh runs in
 * [scope] (a scope with a SupervisorJob that `:app` gives), from the token request to the vault write, with its own limit
 * of 20 s, and every caller awaits it: so a caller's stop or ProfileClient's limit of 30 s ends the caller's wait but not
 * the refresh, and a rotated refresh token is stored before the lock is released, also when the request that caused the
 * refresh is gone. A refresh that fails for now keeps a token that is still valid; once the token has expired, the request
 * fails with the refresh's error, or with SignInExpired when the provider refused the refresh with a 4xx other than 429.
 */
class CredentialManager(
    private val vault: CredentialVault,
    private val http: ProviderHttp,
    private val catalog: Catalog,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val refreshers: (Profile) -> TokenRefresher? = { SignInRegistry.refresher(it.providerId, it.choiceId, http, clock) },
) {
    private val cache = ConcurrentHashMap<String, Credential>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * After an early refresh of a credential failed for a moment, the time before which the next early one waits; kept per
     * credential, because each call resolves a new RequestAuth. An expired token and a 401 refresh at once all the same.
     */
    private val earlyAgainAt = ConcurrentHashMap<String, Long>()

    /**
     * The credential with [id], from memory or from the vault; null when it is missing or could not be read. A read from
     * the vault takes the credential's lock, so that a store, delete or refresh cannot run between the vault's read and
     * the cache's write and be undone in memory.
     */
    suspend fun read(id: String): Credential? = cache[id] ?: lock(id).withLock { readLocked(id) }

    /** Called with the credential's lock held. */
    private suspend fun readLocked(id: String): Credential? = cache[id] ?: vault.read(id)?.also { cache[id] = it }

    /**
     * Stores [credential] under [id], or under a new ID, and returns the ID; an existing ID is replaced. It waits for a
     * running refresh of [id], so that the refresh cannot overwrite the new credential with the old one.
     */
    suspend fun store(credential: Credential, id: String? = null): String {
        val key = id ?: "credential-${UUID.randomUUID()}"
        lock(key).withLock {
            write(key, credential)
            earlyAgainAt.remove(key)
        }
        return key
    }

    /** Waits for a running refresh of [id], so that the refresh cannot write the credential back after the delete. */
    suspend fun delete(id: String) {
        lock(id).withLock {
            vault.delete(id)
            cache.remove(id)
            earlyAgainAt.remove(id)
        }
    }

    /**
     * Keeps [credential] in memory only, under a new ID, for the add flow's check before the owner saves the profile
     * (design D10): Kestrel forgets it with [release], or when its process ends. A held credential is never refreshed, so
     * that nothing is written to the vault under its ID and its refresh token stays unused for the profile that the owner
     * saves; a 401 during the check fails as an expired sign-in.
     */
    fun hold(credential: Credential): String = "$PENDING${UUID.randomUUID()}".also { cache[it] = credential }

    fun release(id: String) {
        cache.remove(id)
    }

    /**
     * The authentication of [profile]'s requests, with [keyHeader] for an API key. A missing or unreadable credential
     * fails each request as SignInExpired, so that the owner signs in again; [RequestAuth.rejected] is Unauthorized for
     * an API key and SignInExpired for every other credential (design D1 and D9).
     */
    suspend fun auth(profile: Profile, keyHeader: KeyHeader): RequestAuth {
        val id = profile.credentialId ?: return NoAuth
        val expired = ProviderError.SignInExpired(profile.providerId)
        return when (val credential = read(id)) {
            null -> Fails(expired)
            is Credential.ApiKey -> ApiKeyAuth(credential.key, keyHeader)
            is Credential.GitHubToken -> Bearer(credential.token, expired)
            is Credential.AwsAccessKeys -> profile.region?.let { Rejecting(SigV4Auth(credential, it, clock = clock), expired) } ?: Fails(expired)
            is Credential.ServiceAccountKey -> Rejecting(ServiceAccountAuth(credential, http, clock), expired)
            // A held credential is not saved yet: a refresh would write it to the vault and use up the refresh token that the
            // add flow saves later (design D10).
            is Credential.OAuthToken -> TokenAuth(id, if (id.startsWith(PENDING)) null else refreshers(profile), expired)
        }
    }

    private suspend fun refresh(id: String, used: String, refresher: TokenRefresher): RefreshResult =
        scope.async { refreshNow(id, used, refresher) }.await()

    private suspend fun refreshNow(id: String, used: String, refresher: TokenRefresher): RefreshResult =
        lock(id).withLock {
            val current = readLocked(id) as? Credential.OAuthToken ?: return@withLock RefreshResult.Expired
            // Another request refreshed the token while this one waited.
            if (current.access != used) return@withLock RefreshResult.Refreshed
            val outcome = withTimeoutOrNull(REFRESH_LIMIT_MILLIS) {
                try {
                    refresher.refresh(current)
                } catch (e: ProviderException) {
                    Refresh.ForNow(e.error)
                }
            } ?: Refresh.ForNow(ProviderError.Timeout)
            when (outcome) {
                is Refresh.Done -> {
                    write(id, outcome.token)
                    earlyAgainAt.remove(id)
                    RefreshResult.Refreshed
                }
                is Refresh.ForNow -> RefreshResult.FailedForNow(outcome.error)
                is Refresh.Refused -> {
                    outcome.writeBack?.let { write(id, it) }
                    RefreshResult.Expired
                }
            }
        }

    private fun lock(id: String): Mutex = locks.getOrPut(id) { Mutex() }

    /** Called with the credential's lock held: memory first, so that a failed vault write keeps a rotated token for now. */
    private suspend fun write(id: String, credential: Credential) {
        cache[id] = credential
        vault.write(id, credential)
    }

    /** An OAuth token, refreshed by [refresher] (design D9). */
    private inner class TokenAuth(private val id: String, private val refresher: TokenRefresher?, override val rejected: ProviderError) : RequestAuth {
        @Volatile
        private var lastAccess: String? = null

        override suspend fun headers(request: HttpRequest): Map<String, String> {
            var token = read(id) as? Credential.OAuthToken ?: throw ProviderException(rejected)
            if (refresher != null && due(token)) {
                when (val result = refresh(id, token.access, refresher)) {
                    RefreshResult.Refreshed -> token = read(id) as? Credential.OAuthToken ?: throw ProviderException(rejected)
                    is RefreshResult.FailedForNow -> {
                        // An expired token whose refresh the provider refused with a 4xx works for no request until the
                        // owner signs in again, also when design D9 does not list the refusal.
                        if (expired(token)) throw ProviderException(if (refused(result.error)) rejected else result.error)
                        val wait = (result.error as? ProviderError.RateLimited)?.retryAfterMillis ?: 0L
                        earlyAgainAt[id] = clock() + maxOf(EARLY_RETRY_MILLIS, wait)
                    }
                    RefreshResult.Expired, RefreshResult.NotPossible -> throw ProviderException(rejected)
                }
            }
            lastAccess = token.access
            return mapOf("Authorization" to "Bearer ${token.access}")
        }

        override suspend fun refresh(): RefreshResult {
            val refresher = refresher ?: return RefreshResult.NotPossible
            val used = lastAccess ?: (read(id) as? Credential.OAuthToken)?.access ?: return RefreshResult.Expired
            return refresh(id, used, refresher)
        }

        private fun expired(token: Credential.OAuthToken): Boolean = token.expiresAtMillis?.let { clock() >= it } == true

        /** A refusal of the refresh other than a rate limit, which the refreshers report as a BadRequest for now. */
        private fun refused(error: ProviderError): Boolean = error is ProviderError.BadRequest && error.status in 400..499 && error.status != 429

        private fun due(token: Credential.OAuthToken): Boolean {
            val expiresAt = token.expiresAtMillis ?: return false
            val now = clock()
            if (now >= expiresAt) return true
            return now >= expiresAt - EARLY_MILLIS && now >= (earlyAgainAt[id] ?: 0L) && (token.earliestRefreshAtMillis?.let { now >= it } ?: true)
        }

        override fun toString(): String = "TokenAuth(refreshable=${refresher != null})"
    }

    /** A token that is the bearer of every call and cannot be refreshed, such as Copilot's GitHub token. */
    private class Bearer(private val token: String, override val rejected: ProviderError) : RequestAuth {
        override suspend fun headers(request: HttpRequest): Map<String, String> = mapOf("Authorization" to "Bearer $token")

        override fun toString(): String = "Bearer(length=${token.length})"
    }

    /** Plan 4B's signers, with the rejection of a sign-in rather than of an API key. */
    private class Rejecting(private val inner: RequestAuth, override val rejected: ProviderError) : RequestAuth {
        override suspend fun headers(request: HttpRequest): Map<String, String> = inner.headers(request)

        override suspend fun refresh(): RefreshResult = inner.refresh()

        override fun toString(): String = "Rejecting($inner)"
    }

    /** A credential that is missing or could not be read: every request fails, so that the owner signs in again. */
    private class Fails(override val rejected: ProviderError) : RequestAuth {
        override suspend fun headers(request: HttpRequest): Map<String, String> = throw ProviderException(rejected)

        override fun toString(): String = "Fails($rejected)"
    }

    private companion object {
        const val PENDING = "pending-"
        const val EARLY_MILLIS = 60_000L
        const val EARLY_RETRY_MILLIS = 5_000L
        const val REFRESH_LIMIT_MILLIS = 20_000L
    }
}
