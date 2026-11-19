package dev.kestrel.providers.signin

import dev.kestrel.providers.credentials.Credential
import dev.kestrel.providers.profiles.ChatGptRegistration

/**
 * What a sign-in gives (design D9): the credential, the base URL when the flow found one (MiniMax's `resource_url`), the
 * hosts that the flow checked and that the profile needs later, which go into its discovered hosts (design D16), and the
 * ChatGPT plan's registration. It holds a credential, so toString prints only kinds and counts.
 */
data class SignInResult(
    val credential: Credential,
    val baseUrl: String? = null,
    val hosts: Set<String> = emptySet(),
    val registration: ChatGptRegistration? = null,
) {
    override fun toString(): String =
        "SignInResult(credential=$credential, baseUrl=${if (baseUrl == null) "none" else "set"}, hosts=${hosts.size}, " +
            "registration=$registration)"
}

/** Why a sign-in ended without a credential, in the words that its page explains (design D9 and D18). */
enum class SignInFailure {
    CANCELLED,
    REFUSED,
    EXPIRED,
    NO_CALLBACK,
    UNTRUSTED_ADDRESS,
    REGISTRATION_INCOMPLETE,
    REGISTRATION_REFUSED,
    PLAN_USE_NOT_ALLOWED,
    OTHER_ACCOUNT,
    EXCHANGE_FAILED,

    /** Android ended the sign-in's foreground service after its daily time (Android 15 and later). */
    TIMED_OUT,
}

/**
 * A sign-in that ended for a reason that its page explains. Transport and HTTP failures stay ProviderException. A
 * [registration] carries the ChatGPT plan's issued client ID, which the pending sign-in keeps for a retry (design D9).
 */
class SignInFailed(val kind: SignInFailure, val registration: ChatGptRegistration? = null) : Exception("sign-in failed") {
    override fun toString(): String = "SignInFailed(kind=$kind, registration=$registration)"
}
