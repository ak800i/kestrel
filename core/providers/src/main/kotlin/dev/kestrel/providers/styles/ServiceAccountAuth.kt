package dev.kestrel.providers.styles

import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.http.ClockSkew
import dev.kestrel.providers.http.ErrorCodes
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.http.RefreshResult
import dev.kestrel.providers.http.RequestAuth
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import kotlin.math.abs

/**
 * Vertex's credential (design D9, research r35): a JWT signed with RS256 by the service account's key, exchanged at
 * `https://oauth2.googleapis.com/token` for a token of one hour, which is reused until 60 s before it expires. The token
 * lives in [tokens], which by default all instances of the process share, so that a new instance for each request (as
 * plan 4C builds them) still reuses it. The JWT's `iat` lies 60 s in the past, so that a phone whose clock runs fast is
 * not refused, and its header names the key's `kid` when the file has one. Google's `invalid_grant` (a deleted or
 * disabled key) and any other refusal mean that the owner imports a new file; a network failure, a 429 or a 5xx keeps the
 * key (design D9's FailedForNow). Google also answers `invalid_grant` when the phone's clock is far off: when the reply's
 * `Date` differs from the clock that signed the JWT by more than [SKEW_LIMIT_MILLIS], the JWT is signed once more with the
 * clock moved by that difference, as BedrockErrors does for AWS, and only a second refusal maps as above. A corrected
 * exchange that succeeds keeps its offset in [skew] (the process's [ClockSkew] by default), so that every later JWT is
 * signed with `clock() + offset` at once. Neither the `Date` nor
 * Google's text is logged or kept. The token is never kept in the instance, only in [tokens].
 */
class ServiceAccountAuth(
    private val key: Credential.ServiceAccountKey,
    private val http: ProviderHttp,
    private val clock: () -> Long = System::currentTimeMillis,
    private val tokens: ServiceAccountTokens = ServiceAccountTokens.SHARED,
    private val skew: ClockSkew = ClockSkew.SHARED,
) : RequestAuth {
    private val identity = "${key.clientEmail}\n${key.privateKeyId ?: sha256(key.privateKeyPem)}\n$SCOPE\n$TOKEN_URL"

    override val rejected: ProviderError get() = ProviderError.SignInExpired(PROVIDER)

    override suspend fun headers(request: HttpRequest): Map<String, String> {
        val current = tokens.mutex.withLock {
            tokens.get(identity, clock() + EARLY_MILLIS) ?: when (val result = exchange()) {
                is Exchanged.Token -> result.value
                is Exchanged.Failed -> throw ProviderException((result.result as? RefreshResult.FailedForNow)?.error ?: rejected)
            }
        }
        return mapOf("Authorization" to "Bearer $current")
    }

    /** Always exchanges again, because the provider refused the token that [headers] gave. */
    override suspend fun refresh(): RefreshResult = tokens.mutex.withLock {
        tokens.remove(identity)
        when (val result = exchange()) {
            is Exchanged.Token -> RefreshResult.Refreshed
            is Exchanged.Failed -> result.result
        }
    }

    /** What one exchange gave: the new token, which is also in [tokens], or the failure. */
    private sealed interface Exchanged {
        class Token(val value: String) : Exchanged {
            override fun toString(): String = "Token(length=${value.length})"
        }

        class Failed(val result: RefreshResult) : Exchanged
    }

    /**
     * Called with the cache's mutex held. [offsetMillis] is the correction of the phone's clock for the second attempt
     * after Google refused a JWT that the phone signed with a clock far off; null on the first attempt, which is signed
     * with [skew]'s stored offset.
     */
    private suspend fun exchange(offsetMillis: Long? = null): Exchanged {
        val used = offsetMillis ?: skew.offsetMillis
        val assertion = try {
            jwt(clock() + used)
        } catch (e: GeneralSecurityException) {
            return Exchanged.Failed(RefreshResult.Expired)
        } catch (e: IllegalArgumentException) {
            return Exchanged.Failed(RefreshResult.Expired)
        }
        val form = "grant_type=${SigV4Auth.encode(GRANT_TYPE)}&assertion=${SigV4Auth.encode(assertion)}"
        val reply = try {
            http.exchange(HttpRequest("POST", TOKEN_URL.toHttpUrl(), mapOf("accept" to "application/json"), form.encodeToByteArray(), "application/x-www-form-urlencoded"))
        } catch (e: ProviderException) {
            return Exchanged.Failed(RefreshResult.FailedForNow(e.error))
        }
        return when (reply.status) {
            in 200..299 -> {
                val body = try {
                    JSON.parseToJsonElement(reply.text()) as? JsonObject
                } catch (e: SerializationException) {
                    null
                }
                val access = (body?.get("access_token") as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: return Exchanged.Failed(RefreshResult.FailedForNow(ProviderError.Malformed))
                val seconds = (body["expires_in"] as? JsonPrimitive)?.longOrNull ?: 3600
                tokens.put(identity, access, clock() + seconds * 1000)
                offsetMillis?.let(skew::learn)
                Exchanged.Token(access)
            }
            429 -> Exchanged.Failed(RefreshResult.FailedForNow(ProviderError.RateLimited(http.retryAfterMillis(reply.headers["Retry-After"]), ErrorCodes.from(reply))))
            in 500..599 -> Exchanged.Failed(RefreshResult.FailedForNow(ProviderError.ServerError(reply.status)))
            else -> {
                val server = reply.headers.getDate("Date")?.let { it.time - clock() }
                if (offsetMillis == null && server != null && abs(server - used) > SKEW_LIMIT_MILLIS && ErrorCodes.from(reply) == INVALID_GRANT) {
                    exchange(server)
                } else {
                    Exchanged.Failed(RefreshResult.Expired)
                }
            }
        }
    }

    private fun jwt(nowMillis: Long): String {
        val issuedAt = nowMillis / 1000 - 60
        val header = buildJsonObject {
            put("alg", "RS256")
            put("typ", "JWT")
            key.privateKeyId?.let { put("kid", it) }
        }
        val claims = buildJsonObject {
            put("iss", key.clientEmail)
            put("scope", SCOPE)
            put("aud", TOKEN_URL)
            put("iat", issuedAt)
            put("exp", issuedAt + 3600)
        }
        val input = "${base64Url(header.toString().encodeToByteArray())}.${base64Url(claims.toString().encodeToByteArray())}"
        val der = Base64.getMimeDecoder().decode(key.privateKeyPem.replace(Regex("-----[A-Z ]+-----"), ""))
        val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(privateKey)
            update(input.encodeToByteArray())
        }.sign()
        return "$input.${base64Url(signature)}"
    }

    private fun sha256(text: String): String = base64Url(MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()))

    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    override fun toString(): String = "ServiceAccountAuth(key=$key, tokens=$tokens)"

    companion object {
        const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val SCOPE = "https://www.googleapis.com/auth/cloud-platform"
        private const val PROVIDER = "google-vertex"
        private const val GRANT_TYPE = "urn:ietf:params:oauth:grant-type:jwt-bearer"
        private const val EARLY_MILLIS = 60_000L
        private const val SKEW_LIMIT_MILLIS = 5 * 60 * 1000L
        private const val INVALID_GRANT = "invalid_grant"
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}

/**
 * The access tokens of service accounts (design D9), kept only in memory, keyed by the account's e-mail, the key (its
 * `private_key_id`, or a SHA-256 of the key when the file has no ID), the scope and the token URI, and dropped when they
 * expire. At most [capacity] tokens are kept; the least recently used goes first.
 * One [mutex] lets one exchange run at a time, so that callers who wait use its token. The map has its own lock, because
 * a read of an access-ordered map changes it, so that [size] and toString can be read at any time. Nothing of a token is
 * printed.
 */
class ServiceAccountTokens(private val capacity: Int = 4) {
    private class Entry(val token: String, val expiresAtMillis: Long)

    private val entries = object : LinkedHashMap<String, Entry>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > capacity
    }

    internal val mutex = Mutex()

    /** The token for [identity] that is still valid at [atMillis], or null. Called with [mutex] held. */
    internal fun get(identity: String, atMillis: Long): String? = synchronized(entries) {
        val entry = entries[identity] ?: return null
        if (atMillis < entry.expiresAtMillis) return entry.token
        entries.remove(identity)
        null
    }

    internal fun put(identity: String, token: String, expiresAtMillis: Long) {
        synchronized(entries) { entries[identity] = Entry(token, expiresAtMillis) }
    }

    internal fun remove(identity: String) {
        synchronized(entries) { entries.remove(identity) }
    }

    /** The number of tokens kept. */
    val size: Int get() = synchronized(entries) { entries.size }

    override fun toString(): String = "ServiceAccountTokens($size kept)"

    companion object {
        /** The cache of the process, which every [ServiceAccountAuth] uses unless it is given another. */
        val SHARED = ServiceAccountTokens()
    }
}
