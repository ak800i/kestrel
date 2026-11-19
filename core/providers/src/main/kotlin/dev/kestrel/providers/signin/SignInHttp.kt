package dev.kestrel.providers.signin

import dev.kestrel.providers.http.HttpReply
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl
import okio.Buffer

/** The requests and answers of the sign-in flows and refreshers, which read OAuth's answers through ProviderHttp.exchange. */
internal object SignInHttp {
    private val json = Json { isLenient = true }

    private val ACCEPT_JSON = mapOf("Accept" to "application/json")

    fun get(url: HttpUrl, headers: Map<String, String> = ACCEPT_JSON): HttpRequest = HttpRequest("GET", url, headers)

    /** A form POST (`application/x-www-form-urlencoded`), as OAuth's token and device endpoints take it. */
    fun form(url: HttpUrl, vararg fields: Pair<String, String>, headers: Map<String, String> = ACCEPT_JSON): HttpRequest {
        val body = FormBody.Builder().apply { fields.forEach { (name, value) -> add(name, value) } }.build()
        val bytes = Buffer().also { body.writeTo(it) }.readByteArray()
        return HttpRequest("POST", url, headers, bytes, "application/x-www-form-urlencoded")
    }

    fun json(url: HttpUrl, body: JsonObject): HttpRequest =
        HttpRequest("POST", url, ACCEPT_JSON, body.toString().encodeToByteArray(), "application/json")

    /** The reply's body as a JSON object, or null when it is not one. */
    fun parse(reply: HttpReply): JsonObject? =
        try {
            json.parseToJsonElement(reply.text()) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        }

    /**
     * The error of a reply that a flow cannot use: a rate limit, a server error, or else a bad request. A rate limit's
     * `Retry-After` is read by [http], in seconds or as an HTTP date, and a negative value is none.
     */
    fun failure(reply: HttpReply, http: ProviderHttp): ProviderException = ProviderException(
        when {
            reply.status == 429 -> ProviderError.RateLimited(http.retryAfterMillis(reply.headers["Retry-After"]))
            reply.status in 500..599 -> ProviderError.ServerError(reply.status)
            else -> ProviderError.BadRequest(reply.status, null)
        },
    )

    /** Whether a status means "try again later": a rate limit or a server error. */
    fun temporary(status: Int): Boolean = status == 429 || status in 500..599

    /**
     * The error code of an OAuth answer, in lower case: `error` as a string, else `error.code` of an error object, else a
     * top-level `code`, as OpenAI's Codex CLI reads the answers of the same issuer.
     */
    fun errorCode(body: JsonObject?): String? =
        (body?.string("error") ?: body?.obj("error")?.string("code") ?: body?.string("code"))?.lowercase()
}

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.number(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

/** Whether [host] is [domain] or one of its subdomains. */
internal fun hostIn(host: String, domain: String): Boolean = host == domain || host.endsWith(".$domain")
