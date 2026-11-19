package dev.kestrel.app.providers

import dev.kestrel.providers.http.ProviderError
import org.junit.Assert.assertEquals
import org.junit.Test

/** The sentences of the Providers page (design D17 and D18). */
class ProviderTextsTest {
    @Test
    fun aRefusalNamesTheKeyForAKeyAndTheAccountForASignIn() {
        val forbidden = ProviderError.QuotaOrPolicy(403)
        val account = "Copilot refused the account that you signed in with. Check its subscription, or sign in with another account."

        assertEquals(ProviderTexts.KEY_REFUSED, ProviderTexts.check(forbidden, "Groq"))
        assertEquals(ProviderTexts.KEY_REFUSED, ProviderTexts.check(ProviderError.Unauthorized, "Groq"))
        assertEquals(account, ProviderTexts.check(forbidden, "Copilot", signIn = true))
        assertEquals(account, ProviderTexts.check(ProviderError.Unauthorized, "Copilot", signIn = true))
        // A ChatGPT account without a plan keeps its own sentence, and other errors are the spoken ones.
        assertEquals(ProviderTexts.NOT_ELIGIBLE, ProviderTexts.check(ProviderError.QuotaOrPolicy(403, "subscription_sharing_user_not_eligible"), "OpenAI", signIn = true))
        assertEquals(ProviderTexts.spoken(ProviderError.Timeout, "Copilot"), ProviderTexts.check(ProviderError.Timeout, "Copilot", signIn = true))
    }
}
