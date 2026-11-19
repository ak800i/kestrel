package dev.kestrel.providers.styles

import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.RequestAuth
import okhttp3.HttpUrl
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Signs a request with AWS Signature Version 4 for IAM access keys (design D9, research r34): Bedrock's runtime and its
 * control plane both use the signing name `bedrock`, and the region is the host's. It signs `host`, `x-amz-date` and, for
 * temporary keys, `x-amz-security-token`. The canonical path encodes the request's already encoded path once more, as the
 * AWS SDK for Java v2 does for every service but S3 (`V4CanonicalRequest.getCanonicalUri` with `doubleUrlEncode`), so a
 * model ID that the URL carries as `%3A` is signed as `%253A`. AWS's SigV4 test-suite vectors check the rest; no live
 * Bedrock test checks a real model ID's encoding (design D19).
 */
class SigV4Auth(
    private val keys: Credential.AwsAccessKeys,
    private val region: String,
    private val service: String = "bedrock",
    private val clock: () -> Long = System::currentTimeMillis,
) : RequestAuth {
    /** Refused keys need new keys, so a refusal is a sign-in that the owner must renew (design D9). */
    override val rejected: ProviderError get() = ProviderError.SignInExpired("amazon-bedrock")

    override suspend fun headers(request: HttpRequest): Map<String, String> = sign(request.method, request.url, request.body ?: ByteArray(0))

    internal fun sign(method: String, url: HttpUrl, body: ByteArray): Map<String, String> {
        val amzDate = DATE_TIME.format(Instant.ofEpochMilli(clock()))
        val date = amzDate.take(8)
        val signed = signedHeaders(url, amzDate)
        val scope = "$date/$region/$service/aws4_request"
        val stringToSign = listOf("AWS4-HMAC-SHA256", amzDate, scope, hex(sha256(canonicalRequest(method, url, body, amzDate).encodeToByteArray()))).joinToString("\n")
        var key = hmac("AWS4${keys.secretAccessKey}".encodeToByteArray(), date)
        for (part in listOf(region, service, "aws4_request")) key = hmac(key, part)
        val signature = hex(hmac(key, stringToSign))
        val headers = linkedMapOf(
            "x-amz-date" to amzDate,
            "Authorization" to "AWS4-HMAC-SHA256 Credential=${keys.accessKeyId}/$scope, SignedHeaders=${signed.keys.joinToString(";")}, Signature=$signature",
        )
        keys.sessionToken?.let { headers["x-amz-security-token"] = it }
        return headers
    }

    /** The six lines of SigV4's canonical request. */
    internal fun canonicalRequest(method: String, url: HttpUrl, body: ByteArray, amzDate: String): String {
        val headers = signedHeaders(url, amzDate)
        val query = (0 until url.querySize)
            .map { encode(url.queryParameterName(it)) to encode(url.queryParameterValue(it).orEmpty()) }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { "${it.first}=${it.second}" }
        return listOf(
            method,
            encode(url.encodedPath.ifEmpty { "/" }, keepSlash = true),
            query,
            headers.entries.joinToString("") { "${it.key}:${it.value}\n" },
            headers.keys.joinToString(";"),
            hex(sha256(body)),
        ).joinToString("\n")
    }

    private fun signedHeaders(url: HttpUrl, amzDate: String): Map<String, String> {
        val headers = sortedMapOf("host" to if (url.port == HttpUrl.defaultPort(url.scheme)) url.host else "${url.host}:${url.port}", "x-amz-date" to amzDate)
        keys.sessionToken?.let { headers["x-amz-security-token"] = it.trim() }
        return headers
    }

    override fun toString(): String = "SigV4Auth(region=$region, service=$service, keys=$keys)"

    companion object {
        private val DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

        /** AWS's URI encoding (RFC 3986): only `A-Z a-z 0-9 - _ . ~` stay, and `/` too when [keepSlash] is true. */
        fun encode(value: String, keepSlash: Boolean = false): String = buildString {
            for (byte in value.encodeToByteArray()) {
                val char = (byte.toInt() and 0xff).toChar()
                if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in "-_.~" || (keepSlash && char == '/')) {
                    append(char)
                } else {
                    append('%').append("%02X".format(byte.toInt() and 0xff))
                }
            }
        }

        private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

        private fun hmac(key: ByteArray, data: String): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data.encodeToByteArray())

        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
