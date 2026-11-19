package dev.kestrel.app.providers

import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.signin.SignInFailure

/**
 * The sentences about providers that Kestrel says or shows (design D9, D17 and D18). Sub-project 5 speaks [spoken] for a
 * failed call; the Providers page shows [signIn] and [check]. They name the provider by its label and hold no data of
 * the owner's.
 */
internal object ProviderTexts {
    const val PLAN_LIMIT = "Your ChatGPT plan's usage limit is reached."
    const val NOT_ELIGIBLE = "This ChatGPT account has no plan that Kestrel can use (Plus or Pro)."
    const val KEY_CHARACTERS = "The key has a character that keys do not have. Paste it again."
    const val KEY_REFUSED = "The key was refused."
    const val SIGN_OUT_UNCONFIRMED = "OpenAI did not confirm the sign-out. You can disconnect Kestrel in ChatGPT's settings."

    /** A failure that no other sentence names, such as an error of Android's Keystore; its class is logged elsewhere. */
    const val FAILED = "Kestrel could not do this. Try again."

    /** Where the owner manages Kestrel's use of the ChatGPT plan, and disconnects it (OpenAI's "Accounts and sessions"). */
    const val CHATGPT_USAGE_URL = "https://chatgpt.com/settings/usage"

    /** The sentence that sub-project 5 speaks when a call to [provider] fails with [error] (spec 14.2). */
    fun spoken(error: ProviderError, provider: String): String = when (error) {
        ProviderError.Unauthorized -> "$provider refused the key. Open Kestrel to change it."
        is ProviderError.SignInExpired -> "Your sign-in to $provider expired. Open Kestrel to sign in again."
        is ProviderError.SignInSwitchedOff -> "The sign-in to $provider is switched off in Kestrel's settings."
        is ProviderError.RateLimited -> "$provider is busy. Try again in a moment."
        is ProviderError.ServerError -> "$provider has a problem. Try again later."
        ProviderError.Timeout -> "$provider did not answer in time."
        ProviderError.Unreachable -> "I can't reach $provider. Check the network."
        ProviderError.NotAllowed -> "Kestrel's settings do not allow $provider's address."
        ProviderError.LocalNetworkNotAllowed -> "Kestrel may not reach servers on the local network. Open Kestrel to allow it."
        is ProviderError.QuotaOrPolicy -> when (error.code) {
            "subscription_sharing_usage_limit_exceeded" -> PLAN_LIMIT
            "subscription_sharing_user_not_eligible" -> NOT_ELIGIBLE
            else -> if (error.status == 429) PLAN_LIMIT else "$provider refused the request: the credits are used up, or a policy blocks the model."
        }
        is ProviderError.BadRequest -> "$provider could not use the request."
        ProviderError.BadCredential -> "The key for $provider has a character that keys do not have. Open Kestrel to change it."
        ProviderError.Malformed -> "$provider gave an answer that Kestrel cannot read."
    }

    /** What the sign-in page says when a sign-in ended without a credential (design D9 and D18). */
    fun signIn(failure: SignInFailure, provider: String): String = when (failure) {
        SignInFailure.CANCELLED -> "You cancelled the sign-in."
        SignInFailure.REFUSED -> "$provider refused the sign-in."
        SignInFailure.EXPIRED -> "The code expired before the sign-in was approved. Start again."
        SignInFailure.NO_CALLBACK -> "The browser did not come back to Kestrel within 5 minutes."
        SignInFailure.UNTRUSTED_ADDRESS -> if (provider == "Copilot") {
            "Copilot returned an address that Kestrel does not trust."
        } else {
            "$provider's sign-in returned an address that Kestrel does not trust."
        }
        SignInFailure.REGISTRATION_INCOMPLETE -> "OpenAI did not finish Kestrel's registration. Try again."
        SignInFailure.REGISTRATION_REFUSED -> "OpenAI did not accept Kestrel's registration."
        SignInFailure.PLAN_USE_NOT_ALLOWED -> "ChatGPT plan use was not allowed."
        SignInFailure.OTHER_ACCOUNT -> "This is another ChatGPT account than the one Kestrel is registered with."
        SignInFailure.EXCHANGE_FAILED -> "The sign-in did not finish."
        SignInFailure.TIMED_OUT -> "Android ended the sign-in, because it ran for too long. Start it again."
        SignInFailure.NOT_STORED -> "The sign-in worked, but Kestrel could not store it. Try again."
    }

    /** What the add flow says when its check failed with [error]. */
    fun check(error: ProviderError, provider: String): String = when {
        error == ProviderError.Unauthorized -> KEY_REFUSED
        error is ProviderError.QuotaOrPolicy && error.status == 403 && error.code != "subscription_sharing_user_not_eligible" -> KEY_REFUSED
        else -> spoken(error, provider)
    }
}
