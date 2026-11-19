package dev.kestrel.providers.styles

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
 * Neither the `Date` nor AWS's message is logged or kept.
 */
internal object BedrockErrors {
    const val SKEW_LIMIT_MILLIS = 5 * 60 * 1000L

    private const val INVALID_SIGNATURE = "InvalidSignatureException"
    private val TOKEN_ERRORS = setOf("ExpiredTokenException", "UnrecognizedClientException", INVALID_SIGNATURE)

    /**
     * Sends [request] through [http] with the rules above; [corrected] gets the clock offset that a successful second
     * attempt used, so that the caller can sign its next requests with it.
     */
    suspend fun send(http: ProviderHttp, request: HttpRequest, auth: RequestAuth, corrected: (Long) -> Unit = {}): HttpReply =
        try {
            http.send(request, auth)
        } catch (e: ProviderException) {
            val offset = e.serverClockOffsetMillis
            if (code(e.error) != INVALID_SIGNATURE || offset == null || abs(offset - request.clockOffsetMillis) <= SKEW_LIMIT_MILLIS) throw map(e, auth)
            try {
                http.send(request.withClockOffset(offset), auth).also { corrected(offset) }
            } catch (again: ProviderException) {
                throw map(again, auth)
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
