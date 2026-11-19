package dev.kestrel.providers.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reads the machine code of an error reply (design D17): AWS's `x-amzn-ErrorType` header, then `error.code`, `error.type`,
 * a top-level string `error` (OAuth, RFC 6749 §5.2), Google's ErrorInfo reason `API_KEY_INVALID` in `error.details`,
 * Gemini's `error.status` and AWS's `__type`. AWS's values lose a
 * namespace before `#` and everything from `:` on, as AWS's SDKs read them. A code is kept only when it matches
 * `^[A-Za-z0-9_.:-]{1,64}$`, so no message or other text of the provider can pass as a code.
 */
object ErrorCodes {
    private val CODE = Regex("^[A-Za-z0-9_.:-]{1,64}$")
    private val json = Json { isLenient = true }

    fun from(reply: HttpReply): String? {
        reply.headers["x-amzn-ErrorType"]?.let { aws(it) }?.let { return it }
        val body = try {
            json.parseToJsonElement(reply.text()) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        } ?: return null
        val error = body["error"]
        val candidates = listOf(
            (error as? JsonObject)?.string("code"),
            (error as? JsonObject)?.string("type"),
            error.stringValue(),
            (error as? JsonObject)?.let(::googleKeyReason),
            (error as? JsonObject)?.string("status"),
            body.string("__type")?.let(::aws),
        )
        return candidates.firstNotNullOfOrNull { it?.takeIf(CODE::matches) }
    }

    private fun aws(value: String): String? = value.substringAfter('#').substringBefore(':').trim().takeIf(CODE::matches)

    /** Google's `error.details[]` ErrorInfo reason `API_KEY_INVALID`, which says that the API key was refused. */
    private fun googleKeyReason(error: JsonObject): String? =
        (error["details"] as? JsonArray)?.filterIsInstance<JsonObject>()
            ?.firstOrNull { it.string("@type")?.endsWith("google.rpc.ErrorInfo") == true && it.string("reason") == API_KEY_INVALID }
            ?.let { API_KEY_INVALID }

    private const val API_KEY_INVALID = "API_KEY_INVALID"

    private fun JsonObject.string(key: String): String? = this[key].stringValue()

    private fun JsonElement?.stringValue(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
}
