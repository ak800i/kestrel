package dev.kestrel.providers.profiles

import dev.kestrel.providers.catalog.Catalog
import kotlinx.serialization.Serializable

/**
 * Every provider setting that Kestrel stores as one encrypted setting (design D13): the profiles, the default profile, the
 * switches of the borrowed sign-ins keyed by identity (design D9; a missing key means the choice's `defaultOn`), the
 * ChatGPT plan's host ID and its kept registration (design D9). Credentials and model lists are stored elsewhere.
 */
@Serializable
data class ProvidersSetting(
    val profiles: List<Profile> = emptyList(),
    val defaultProfileId: String? = null,
    val signInSwitches: Map<String, Boolean> = emptyMap(),
    val chatgptHostId: String? = null,
    val chatgptRegistration: ChatGptRegistration? = null,
) {
    val defaultProfile: Profile? get() = profiles.firstOrNull { it.id == defaultProfileId }

    /** Whether the borrowed sign-in [identity] may be used: the owner's switch, else the choice's `defaultOn`. */
    fun switchedOn(identity: String, catalog: Catalog): Boolean =
        signInSwitches[identity]
            ?: catalog.providers.asSequence().flatMap { it.authChoices }.mapNotNull { it.settings }
                .firstOrNull { it.borrowedIdentity == identity }?.defaultOn
            ?: true

    /** Whether [profile] may be used now: its borrowed identity, if it has one, is switched on. */
    fun usable(profile: Profile, catalog: Catalog): Boolean {
        val identity = catalog.choice(profile.providerId, profile.choiceId)?.settings?.borrowedIdentity ?: return true
        return switchedOn(identity, catalog)
    }

    override fun toString(): String =
        "ProvidersSetting(profiles=$profiles, defaultProfileId=$defaultProfileId, signInSwitches=$signInSwitches, " +
            "chatgptHostId=${if (chatgptHostId == null) "none" else "set"}, chatgptRegistration=$chatgptRegistration)"
}

/**
 * The ChatGPT plan's registration of Kestrel (design D9): the issued client ID (`oaiapp_…`), which is bound to the owner's
 * account, and the ID token's `sub`. It is kept after its profile is deleted. toString prints neither.
 */
@Serializable
data class ChatGptRegistration(val clientId: String, val subject: String?) {
    override fun toString(): String =
        "ChatGptRegistration(clientIdLength=${clientId.length}, subject=${if (subject == null) "none" else "set"})"
}
