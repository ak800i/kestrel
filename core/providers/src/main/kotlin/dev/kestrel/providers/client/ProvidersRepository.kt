package dev.kestrel.providers.client

import dev.kestrel.providers.profiles.Profile
import dev.kestrel.providers.profiles.ProvidersSetting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Gives sub-project 5 the client of a profile (design D13 and D17). [stored] is the stored provider setting, [applied]
 * the one whose hosts the allowlist holds; [newClient] builds a profile's client. [defaultClient] is null only when there
 * is no default profile (spec 11.1); the client of a profile whose borrowed sign-in is switched off fails each call with
 * SignInSwitchedOff, so that sub-project 5 can say why. [planLimitReached] holds the IDs of the ChatGPT plan profiles
 * whose plan's usage limit a call reached, until a later call on them works; Kestrel never moves such a request to
 * another profile by itself. For a moment after the process starts, until the settings store has answered, both
 * functions return null although a profile exists; sub-project 5 waits for [stored] to hold a setting before it says
 * that no profile is set up.
 */
class ProvidersRepository(
    private val stored: StateFlow<ProvidersSetting?>,
    private val applied: StateFlow<ProvidersSetting?>,
    private val newClient: (Profile, PlanLimits) -> ProfileClient,
) {
    private val limited = MutableStateFlow<Set<String>>(emptySet())

    val planLimitReached: StateFlow<Set<String>> = limited.asStateFlow()

    private val limits = object : PlanLimits {
        override fun reached(profileId: String) = limited.update { it + profileId }

        override fun cleared(profileId: String) = limited.update { it - profileId }
    }

    private fun setting(): ProvidersSetting? = stored.value ?: applied.value

    fun defaultClient(): ProfileClient? = setting()?.defaultProfile?.let { newClient(it, limits) }

    fun client(profileId: String): ProfileClient? =
        setting()?.profiles?.firstOrNull { it.id == profileId }?.let { newClient(it, limits) }
}
