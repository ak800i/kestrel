package dev.kestrel.providers.styles

import dev.kestrel.providers.http.ClockSkew
import dev.kestrel.providers.http.HttpReply
import dev.kestrel.providers.http.HttpRequest
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.http.ProviderException
import dev.kestrel.providers.http.ProviderHttp
import dev.kestrel.providers.http.RequestAuth
import kotlin.math.abs

/**
 * What Bedrock's runtime (BedrockAdapter) and its control plane (BedrockModels) do with AWS's refusals (design D9, research
 * r34). AWS's token errors (`ExpiredTokenException`, `UnrecognizedClientException` and `InvalidSignatureException`, at 403
 * or 400) are the credential's own refusal (`rejected`): new keys for IAM keys, and a new key for an API key. AWS also
 * answers `InvalidSignatureException` when the phone's clock is more than 5 minutes away from its own; when the reply's
 * `Date` differs from the clock that signed the request by more than [SKEW_LIMIT_MILLIS], the request is signed once more
 * with the clock moved by that difference, as AWS's SDKs correct a clock skew, and only a second refusal maps as above.
 * The corrected offset is kept in a [ClockSkew] for every later request of the process.
 * Neither the `Date` nor AWS's message is logged or kept.
 */
internal object BedrockErrors {
    const val SKEW_LIMIT_MILLIS = 5 * 60 * 1000L

    private const val INVALID_SIGNATURE = "InvalidSignatureException"
    private val TOKEN_ERRORS = setOf("ExpiredTokenException", "UnrecognizedClientException", INVALID_SIGNATURE)

    /**
     * Sends [request] through [http] with the rules above, signed with [skew]'s stored offset; a corrected attempt that
     * succeeds stores its offset in [skew], so that the next requests are signed with it at once.
     */
    suspend fun send(http: ProviderHttp, request: HttpRequest, auth: RequestAuth, skew: ClockSkew): HttpReply {
        val signed = request.withClockOffset(skew.offsetMillis)
        return try {
            http.send(signed, auth)
        } catch (e: ProviderException) {
            val offset = e.serverClockOffsetMillis
            if (code(e.error) != INVALID_SIGNATURE || offset == null || abs(offset - signed.clockOffsetMillis) <= SKEW_LIMIT_MILLIS) throw map(e, auth)
            try {
                http.send(signed.withClockOffset(offset), auth).also { skew.learn(offset) }
            } catch (again: ProviderException) {
                throw map(again, auth)
            }
        }
    }

    private fun map(e: ProviderException, auth: RequestAuth): ProviderException =
        if (code(e.error) in TOKEN_ERRORS) ProviderException(auth.rejected) else e

    private fun code(error: ProviderError): String? = when (error) {
        is ProviderError.QuotaOrPolicy -> error.code
        is ProviderError.BadRequest -> error.code
        else -> null
    }
}
