package dev.kestrel.providers.cost

import dev.kestrel.providers.catalog.AuthMethod
import dev.kestrel.providers.catalog.CatalogLoader
import dev.kestrel.providers.catalog.LocalServerKind
import dev.kestrel.providers.profiles.Profile
import dev.kestrel.providers.profiles.ProfileModel
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class PriceTableTest {
    private val catalog = CatalogLoader.load(
        """{"schema": 1, "openclawCommit": "e6113549fe8800b4c736c5e8a131b47f2e015143", "providers": [
             {"id": "acme", "plugin": "acme", "label": "Acme", "style": "CHAT_COMPLETIONS", "baseUrl": "https://api.acme.test/v1", "hosts": ["api.acme.test"],
              "authChoices": [{"choiceId": "acme-key", "method": "api-key", "label": "Key"}, {"choiceId": "acme-plan", "method": "oauth", "label": "Plan"}],
              "models": [{"id": "dated", "name": "Dated", "prices": [{"input": 1.0, "output": 2.0, "validUntil": "2026-12-01"}, {"input": 3.0, "output": 4.0, "validFrom": "2026-12-01"}]},
                         {"id": "ended", "name": "Ended", "prices": [{"input": 1.0, "output": 2.0, "validUntil": "2026-12-01"}]},
                         {"id": "zero", "name": "Zero"}, {"id": "gift", "name": "Gift"}]},
             {"id": "github-copilot", "plugin": "github-copilot", "label": "Copilot", "style": "RESPONSES", "baseUrl": "https://api.individual.githubcopilot.com",
              "hosts": [], "authChoices": [{"choiceId": "github-copilot", "method": "device", "label": "Copilot"}], "models": []},
             {"id": "ollama", "plugin": "ollama", "label": "Ollama", "style": "OLLAMA", "baseUrl": null, "hosts": [],
              "authChoices": [], "models": []}]}""",
        """{"schema": 1, "excludeProviders": [], "excludeAuthChoices": [],
            "choices": [{"provider": "acme", "choiceId": "acme-key", "method": "API_KEY"},
                        {"provider": "acme", "choiceId": "acme-plan", "method": "DEVICE_CODE", "planUsage": true},
                        {"provider": "github-copilot", "choiceId": "github-copilot", "method": "DEVICE_CODE"},
                        {"provider": "ollama", "choiceId": "ollama-server", "method": "SERVER_ADDRESS"}],
            "providers": [], "localServers": [], "models": [], "freeModels": [{"provider": "acme", "model": "gift"}],
            "unknownPrice": {"input": 5.0, "output": 25.0, "cacheRead": 0.5, "cacheWrite": 6.25},
            "copilot": {"pricesCopiedOn": "2026-11-17", "pricesSource": "https://docs.github.com/",
                        "models": [{"name": "GPT-6.1 Terra", "prices": [{"input": 1.0, "output": 5.0}]},
                                   {"name": "Claude Sonnet 5.5", "id": "claude-sonnet-5.5", "prices": [{"input": 2.0, "output": 10.0}]}],
                        "lightweight": [], "versatile": [], "runtimeHeaders": {}, "ideHeaders": {}}}""",
    )
    private val table = PriceTable(catalog)
    private val day = LocalDate.parse("2026-11-17")
    private val unknown = PriceChoice(Price(5.0, 25.0, 0.5, 6.25), known = false)

    private fun profile(provider: String, choice: String, typed: Price? = null, baseUrl: String? = null, server: LocalServerKind? = null) =
        Profile("test-1", "Test", provider, choice, AuthMethod.API_KEY, null, baseUrl = baseUrl, localServer = server,
            lightweight = ProfileModel("dated", price = typed), versatile = ProfileModel("other"))

    @Test
    fun aPlanSignInHasNoPerTokenPrice() {
        assertEquals(PriceChoice(Price.FREE, known = true, planUsage = true), table.priceFor(profile("acme", "acme-plan"), "dated", null, day))
    }

    @Test
    fun theOwnersPriceWinsOverTheCatalogs() {
        val typed = Price(0.5, 0.5)

        assertEquals(PriceChoice(typed, known = true), table.priceFor(profile("acme", "acme-key", typed = typed), "dated", null, day))
        assertEquals(unknown, table.priceFor(profile("acme", "acme-key", typed = typed), "zero", null, day))
    }

    @Test
    fun theCatalogsPriceIsTheOneOfTheDay() {
        val key = profile("acme", "acme-key")

        assertEquals(1.0, table.priceFor(key, "dated", null, day).price.input, 0.0)
        assertEquals(1.0, table.priceFor(key, "dated", null, LocalDate.parse("2026-11-30")).price.input, 0.0)
        assertEquals(3.0, table.priceFor(key, "dated", null, LocalDate.parse("2026-12-01")).price.input, 0.0)
        assertEquals(unknown, table.priceFor(key, "ended", null, LocalDate.parse("2026-12-01")))
    }

    @Test
    fun copilotMatchesByIdOrNormalisedNameAndElseUsesTheUnknownPrice() {
        val copilot = profile("github-copilot", "github-copilot")

        assertEquals(PriceChoice(Price(1.0, 5.0), known = true), table.priceFor(copilot, "gpt-6.1-terra", "GPT-6.1 Terra", day))
        assertEquals(PriceChoice(Price(2.0, 10.0), known = true), table.priceFor(copilot, "claude-sonnet-5.5", null, day))
        assertEquals(unknown, table.priceFor(copilot, "gpt-7", "GPT-7", day))
    }

    @Test
    fun aLocalServerAtALocalAddressAndAListedModelAreFreeButAPublicServerIsNot() {
        val lan = profile("ollama", "ollama-server", baseUrl = "http://192.168.1.20:11434", server = LocalServerKind.OLLAMA)
        val ipv6 = lan.copy(baseUrl = "http://[::1]:11434")
        val public = lan.copy(baseUrl = "https://llm.example.com")

        assertEquals(PriceChoice(Price.FREE, known = true), table.priceFor(lan, "llama", null, day))
        assertEquals(PriceChoice(Price.FREE, known = true), table.priceFor(ipv6, "llama", null, day))
        assertEquals(unknown, table.priceFor(public, "llama", null, day))
        assertEquals(PriceChoice(Price.FREE, known = true), table.priceFor(profile("acme", "acme-key"), "gift", null, day))
    }
}
