package dev.kestrel.app.debug

import dev.kestrel.providers.catalog.AuthMethod
import dev.kestrel.providers.catalog.CatalogLoader
import dev.kestrel.providers.profiles.Profile
import dev.kestrel.providers.profiles.ProfileModel
import dev.kestrel.providers.profiles.ProvidersSetting
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The answer of the hook provider-standin, which scripts\connected-test.ps1 and scripts\a71-providers.ps1 compare before
 * and after a run on kestrel-api37: a profile that another one replaced has another ID, and a deleted credential shows as
 * missing.
 */
class ProviderHooksStandInTest {
    private fun profile(id: String, providerId: String = CatalogLoader.COPILOT, credentialId: String? = "credential-1") = Profile(
        id, "Test", providerId, "github-copilot", AuthMethod.DEVICE_CODE, credentialId,
        lightweight = ProfileModel("test-model"), versatile = ProfileModel("test-model"),
    )

    @Test
    fun theAnswerNamesTheDefaultProfilesIdAndWhetherItsCredentialIsStored() {
        val setting = ProvidersSetting(listOf(profile("profile-1"), profile("profile-2", "other-provider")), defaultProfileId = "profile-1")

        assertEquals(
            "standin=off profile=copilot copilot=1 id=profile-1 credential=present",
            ProviderHooks.standInAnswer(on = false, setting = setting, credentialStored = true),
        )
        assertEquals(
            "standin=on profile=copilot copilot=1 id=profile-1 credential=missing",
            ProviderHooks.standInAnswer(on = true, setting = setting, credentialStored = false),
        )
    }

    @Test
    fun withoutADefaultProfileOrACredentialTheAnswerSaysNone() {
        assertEquals(
            "standin=off profile=none copilot=0 id=none credential=none",
            ProviderHooks.standInAnswer(on = false, setting = ProvidersSetting(), credentialStored = null),
        )
        val keyless = ProvidersSetting(listOf(profile("profile-3", "local-server", credentialId = null)), defaultProfileId = "profile-3")
        assertEquals(
            "standin=off profile=other copilot=0 id=profile-3 credential=none",
            ProviderHooks.standInAnswer(on = false, setting = keyless, credentialStored = null),
        )
    }
}
