package dev.kestrel.providers.profiles

import dev.kestrel.providers.catalog.AuthMethod
import dev.kestrel.providers.catalog.CatalogLoader
import dev.kestrel.providers.cost.Price
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvidersSettingTest {
    private val catalog = CatalogLoader.load(
        """{"schema": 1, "openclawCommit": "e6113549fe8800b4c736c5e8a131b47f2e015143", "providers": [
             {"id": "acme", "plugin": "acme", "label": "Acme", "style": "RESPONSES", "baseUrl": "https://api.acme.test/v1", "hosts": ["api.acme.test"],
              "authChoices": [{"choiceId": "acme-key", "method": "api-key", "label": "Key"}, {"choiceId": "acme-borrowed", "method": "oauth", "label": "Sign in"},
                              {"choiceId": "acme-on", "method": "oauth", "label": "Sign in"}],
              "models": []}]}""",
        """{"schema": 1, "excludeProviders": [], "excludeAuthChoices": [],
            "choices": [{"provider": "acme", "choiceId": "acme-key", "method": "API_KEY"},
                        {"provider": "acme", "choiceId": "acme-borrowed", "method": "DEVICE_CODE", "borrowedIdentity": "acme-cli", "defaultOn": false},
                        {"provider": "acme", "choiceId": "acme-on", "method": "DEVICE_CODE", "borrowedIdentity": "acme-ide", "defaultOn": true}],
            "providers": [], "localServers": [], "models": [], "freeModels": [],
            "unknownPrice": {"input": 5.0, "output": 25.0},
            "copilot": {"pricesCopiedOn": "2026-11-17", "pricesSource": "https://docs.github.com/", "models": [], "lightweight": [], "versatile": [],
                        "runtimeHeaders": {}, "ideHeaders": {}}}""",
    )

    private fun profile(id: String, choice: String) =
        Profile(id, "Name of $id", "acme", choice, AuthMethod.DEVICE_CODE, "cred-$id", lightweight = ProfileModel("a"), versatile = ProfileModel("b"))

    @Test
    fun aSwitchFollowsTheOwnersChoiceElseTheChoicesDefault() {
        val setting = ProvidersSetting()

        assertFalse(setting.switchedOn("acme-cli", catalog))
        assertTrue(setting.switchedOn("acme-ide", catalog))
        assertTrue(setting.copy(signInSwitches = mapOf("acme-cli" to true)).switchedOn("acme-cli", catalog))
        assertFalse(setting.copy(signInSwitches = mapOf("acme-ide" to false)).switchedOn("acme-ide", catalog))
    }

    @Test
    fun aProfileIsUsableUnlessItsBorrowedIdentityIsSwitchedOff() {
        val setting = ProvidersSetting(profiles = listOf(profile("p1", "acme-key"), profile("p2", "acme-borrowed")), defaultProfileId = "p2")

        assertTrue(setting.usable(setting.profiles[0], catalog))
        assertFalse(setting.usable(setting.profiles[1], catalog))
        assertEquals("p2", setting.defaultProfile?.id)
    }

    @Test
    fun theSettingSurvivesJsonAndPrintsNoNamesClientIdsOrAccounts() {
        val setting = ProvidersSetting(
            profiles = listOf(profile("p1", "acme-key").copy(lightweight = ProfileModel("secret-model", price = Price(1.0, 2.0)))),
            chatgptHostId = "urn:uuid:00000000-0000-4000-8000-000000000000",
            chatgptRegistration = ChatGptRegistration("oaiapp_secret", "secret-subject"),
        )

        val json = Json.encodeToString(ProvidersSetting.serializer(), setting)
        val printed = setting.toString() + setting.profiles.single().lightweight.toString()

        assertEquals(setting, Json.decodeFromString(ProvidersSetting.serializer(), json))
        assertFalse(printed, Regex("secret|Name of|urn:uuid").containsMatchIn(printed))
        assertEquals(
            "ProvidersSetting(profiles=[Profile(id=p1, provider=acme)], defaultProfileId=null, signInSwitches={}, chatgptHostId=set, " +
                "chatgptRegistration=ChatGptRegistration(clientIdLength=13, subject=set))",
            setting.toString(),
        )
    }

    @Test
    fun aProfileGivesTheModelOfEachRole() {
        val profile = profile("p1", "acme-key")

        assertEquals("a", profile.model(ModelRole.LIGHTWEIGHT).id)
        assertEquals("b", profile.model(ModelRole.VERSATILE).id)
    }
}
