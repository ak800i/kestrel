package dev.kestrel.app.providers

import dev.kestrel.privacy.network.NetworkPolicy
import dev.kestrel.privacy.network.PolicyDecision
import dev.kestrel.providers.catalog.AuthMethod
import dev.kestrel.providers.catalog.CatalogLoader
import dev.kestrel.providers.catalog.LocalServerKind
import dev.kestrel.providers.profiles.Profile
import dev.kestrel.providers.profiles.ProfileModel
import dev.kestrel.providers.profiles.ProvidersSetting
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class ProviderAllowlistTest {
    private val catalog = CatalogLoader.load(
        """{"schema": 1, "openclawCommit": "e6113549fe8800b4c736c5e8a131b47f2e015143", "providers": [
             {"id": "acme", "plugin": "acme", "label": "Acme", "style": "RESPONSES", "baseUrl": "https://api.acme.test/v1", "hosts": ["api.acme.test"],
              "authChoices": [{"choiceId": "acme-key", "method": "api-key", "label": "Key"}, {"choiceId": "acme-cli", "method": "oauth", "label": "CLI"}],
              "models": []},
             {"id": "ollama", "plugin": "ollama", "label": "Ollama", "style": "OLLAMA", "baseUrl": null, "hosts": [], "authChoices": [], "models": []}]}""",
        """{"schema": 1, "excludeProviders": [], "excludeAuthChoices": [],
            "choices": [{"provider": "acme", "choiceId": "acme-key", "method": "API_KEY"},
                        {"provider": "acme", "choiceId": "acme-cli", "method": "DEVICE_CODE", "baseUrl": "https://proxy.acme.test/v1",
                         "refreshHosts": ["auth.acme.test"], "borrowedIdentity": "acme-cli", "defaultOn": false},
                        {"provider": "ollama", "choiceId": "ollama-server", "method": "SERVER_ADDRESS"}],
            "providers": [], "localServers": [], "models": [], "freeModels": [],
            "unknownPrice": {"input": 5.0, "output": 25.0},
            "copilot": {"pricesCopiedOn": "2026-11-17", "pricesSource": "https://docs.github.com/", "models": [], "lightweight": [], "versatile": [],
                        "runtimeHeaders": {}, "ideHeaders": {}}}""",
    )

    private fun profile(id: String, provider: String, choice: String, method: AuthMethod, baseUrl: String? = null) =
        Profile(id, "Test", provider, choice, method, "test-$id", baseUrl = baseUrl, lightweight = ProfileModel("a"), versatile = ProfileModel("b"))

    private val key = profile("test-1", "acme", "acme-key", AuthMethod.API_KEY)
    private val secondKey = profile("test-2", "acme", "acme-key", AuthMethod.API_KEY)
    private val signIn = profile("test-3", "acme", "acme-cli", AuthMethod.DEVICE_CODE).copy(discoveredHosts = listOf("tenant.acme.test"))
    private val lan = profile("test-4", "ollama", "ollama-server", AuthMethod.SERVER_ADDRESS, "http://192.168.1.20:11434").copy(localServer = LocalServerKind.OLLAMA)

    @Test
    fun theHostsOfEveryUsableProfileAreAllowedOnce() {
        val setting = ProvidersSetting(profiles = listOf(key, secondKey, signIn, lan))

        assertEquals(setOf("api.acme.test"), ProviderAllowlist.profileHosts(setting, catalog))
    }

    @Test
    fun aBorrowedSignInAddsItsHostsOnlyWhileItsSwitchIsOn() {
        val on = ProvidersSetting(profiles = listOf(key, signIn), signInSwitches = mapOf("acme-cli" to true))

        assertEquals(
            setOf("api.acme.test", "auth.acme.test", "proxy.acme.test", "tenant.acme.test"),
            ProviderAllowlist.profileHosts(on, catalog),
        )
        assertEquals(setOf("api.acme.test"), ProviderAllowlist.profileHosts(on.copy(signInSwitches = mapOf("acme-cli" to false)), catalog))
    }

    @Test
    fun localServersGoToTheirOwnListAndNothingIsAllowedWithoutProfiles() {
        assertEquals(setOf("192.168.1.20"), ProviderAllowlist.localServers(ProvidersSetting(profiles = listOf(key, lan))))
        assertEquals(emptySet<String>(), ProviderAllowlist.profileHosts(ProvidersSetting(), catalog))
        assertEquals(emptySet<String>(), ProviderAllowlist.localServers(ProvidersSetting()))
    }

    @Test
    fun anUpdateThatFailsLeavesBothListsAsTheyWereAndTheNextOneApplies() {
        val policy = NetworkPolicy()
        val before = ProvidersSetting(profiles = listOf(key, lan))
        val after = ProvidersSetting(profiles = listOf(lan.copy(baseUrl = "http://192.168.1.21:11434")))
        ProviderAllowlist.apply(before, { catalog }, policy)

        assertThrows(IOException::class.java) { ProviderAllowlist.apply(after, { throw IOException("test") }, policy) }

        assertEquals(PolicyDecision.Allowed, policy.check("https://api.acme.test/v1".toHttpUrl()))
        assertEquals(PolicyDecision.Allowed, policy.check("http://192.168.1.20:11434/".toHttpUrl()))
        assertEquals(PolicyDecision.Blocked("192.168.1.21 is not on the allowlist"), policy.check("http://192.168.1.21:11434/".toHttpUrl()))

        ProviderAllowlist.apply(after, { catalog }, policy)

        assertEquals(PolicyDecision.Blocked("api.acme.test is not on the allowlist"), policy.check("https://api.acme.test/v1".toHttpUrl()))
        assertEquals(PolicyDecision.Allowed, policy.check("http://192.168.1.21:11434/".toHttpUrl()))
    }
}
