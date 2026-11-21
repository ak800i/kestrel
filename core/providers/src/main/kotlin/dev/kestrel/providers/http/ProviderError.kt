package dev.kestrel.providers.http

import java.io.IOException

/**
 * Why a provider call failed (design D17). Each kind carries only a status and, where it helps, a machine code that
 * matches `^[A-Za-z0-9_.:-]{1,64}$`; never a body, a provider's message, a URL with a query, a token or a key.
 */
sealed interface ProviderError {
    /** An API key that the provider refused. */
    data object Unauthorized : ProviderError

    /** A sign-in that the provider refused for good, or a credential that is missing or unreadable. */
    data class SignInExpired(val providerId: String) : ProviderError

    data class SignInSwitchedOff(val providerId: String) : ProviderError

    /**
     * The phone could not read or store a credential just now, as when Android's Keystore fails; the credential is kept,
     * and the owner can try again.
     */
    data object CredentialUnavailable : ProviderError

    data class RateLimited(val retryAfterMillis: Long?, val code: String? = null) : ProviderError

    data class ServerError(val status: Int) : ProviderError

    data object Timeout : ProviderError

    /** No network, a DNS failure, a refused connection, a TLS failure or a reset (spec D10 and 11.1). */
    data object Unreachable : ProviderError

    /** The allowlist refused the host: a configuration error (spec 14.2). */
    data object NotAllowed : ProviderError

    data object LocalNetworkNotAllowed : ProviderError

    /** 402, or a 403 that is not a sign-in problem: credits exhausted, or a policy blocks the model. */
    data class QuotaOrPolicy(val status: Int, val code: String? = null) : ProviderError

    /**
     * Any other 4xx. [toolChoice] says that the error named the tool choice, so that the agent may send the request once
     * more with `auto` (design D4 of sub-project 5); no part of the error's message is kept.
     */
    data class BadRequest(val status: Int, val code: String?, val toolChoice: Boolean = false) : ProviderError

    /** A key that OkHttp cannot send as a header. */
    data object BadCredential : ProviderError

    /** A response that Kestrel cannot read. */
    data object Malformed : ProviderError
}

/**
 * Carries a [ProviderError]; its message is the error's kind, status and code only. [serverClockOffsetMillis] is, for an
 * error reply with a `Date` header, the server's time minus the phone's (ProviderHttp's clock), so that a signer whose
 * signature carries the time (AWS's SigV4) can sign once more with a corrected clock; it is never part of the message.
 */
class ProviderException(val error: ProviderError, val serverClockOffsetMillis: Long? = null) : IOException(error.toString())
