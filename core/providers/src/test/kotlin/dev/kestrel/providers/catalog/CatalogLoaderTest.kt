package dev.kestrel.providers.catalog

import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.cost.Price
import dev.kestrel.providers.cost.PriceTier
import dev.kestrel.providers.http.KeyHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogLoaderTest {
    private val providers = """
        {"schema": 1, "openclawCommit": "e6113549fe8800b4c736c5e8a131b47f2e015143", "providers": [
          {"id": "acme", "plugin": "acme", "label": "Acme", "style": "CHAT_COMPLETIONS", "baseUrl": "https://api.acme.test/v1",
           "hosts": ["api.acme.test"], "discovery": "REFRESHABLE",
           "authChoices": [{"choiceId": "acme-api-key", "method": "api-key", "label": "Acme API key"},
                           {"choiceId": "acme-cli", "method": "cli", "label": "Acme CLI"}],
           "models": [{"id": "fast", "name": "Fast", "prices": [{"input": 1.0, "output": 2.0}]},
                      {"id": "plain", "name": "Plain", "compat": {"supportsTools": false}},
                      {"id": "zero", "name": "Zero"}]},
          {"id": "gone", "plugin": "gone", "label": "Gone", "style": null, "baseUrl": null, "hosts": [], "discovery": "STATIC",
           "authChoices": [{"choiceId": "gone-local", "method": "local", "label": "Gone"}], "models": []},
          {"id": "gemini", "plugin": "gemini", "label": "Gemini", "style": "GEMINI", "baseUrl": "https://gemini.test/v1beta",
           "hosts": ["gemini.test"], "discovery": "RUNTIME",
           "authChoices": [{"choiceId": "gemini-api-key", "method": "api-key", "label": "Gemini API key"}],
           "models": [{"id": "g-1", "name": "G 1", "input": ["TEXT", "IMAGE"], "baseUrl": "https://gemini.test/v1beta"}]},
          {"id": "github-copilot", "plugin": "github-copilot", "label": "Copilot", "style": "RESPONSES",
           "baseUrl": "https://api.individual.githubcopilot.com", "hosts": ["api.individual.githubcopilot.com"], "discovery": "REFRESHABLE",
           "authChoices": [{"choiceId": "github-copilot", "method": "device", "label": "GitHub Copilot"}],
           "models": [{"id": "claude-sonnet-5.5", "name": "Claude Sonnet 5.5", "prices": [{"input": 9.0, "output": 9.0}]},
                      {"id": "gpt-6.1-terra", "name": "GPT-6.1 Terra"},
                      {"id": "unlisted", "name": "Unlisted", "prices": [{"input": 9.0, "output": 9.0}]}]}
        ], "skipped": []}
    """.trimIndent()

    private val overrides = """
        {"schema": 1,
         "excludeProviders": [{"id": "gone", "reason": "A desktop program."}],
         "excludeAuthChoices": [{"provider": "acme", "choiceId": "acme-cli", "reason": "A desktop program."}],
         "choices": [
           {"provider": "acme", "choiceId": "acme-api-key", "method": "API_KEY", "label": "Acme key"},
           {"provider": "gemini", "choiceId": "gemini-api-key", "method": "API_KEY", "offered": false},
           {"provider": "cloud", "choiceId": "cloud-keys", "method": "IMPORTED", "keyHeader": "BEARER", "refreshHosts": ["oauth.cloud.test"]},
           {"provider": "github-copilot", "choiceId": "github-copilot", "method": "DEVICE_CODE", "borrowedIdentity": "copilot"}
         ],
         "providers": [
           {"id": "acme", "baseUrl": "https://api2.acme.test/v1", "hosts": ["files.acme.test"], "regions": [{"id": "eu", "label": "Europe", "baseUrl": "https://eu.acme.test/v1"}]},
           {"id": "cloud", "plugin": "gemini", "label": "Cloud", "style": "VERTEX", "baseUrl": "https://{location}-cloud.test",
            "discovery": "STATIC", "modelsFrom": "gemini", "defaultModel": "claude-x", "utilityModel": "g-1",
            "models": [{"id": "claude-x", "name": "Claude X", "style": "ANTHROPIC", "prices": [{"input": 3.0, "output": 15.0}]}]}
         ],
         "localServers": [{"id": "ollama", "label": "Ollama", "kind": "OLLAMA", "style": "OLLAMA", "defaultUrl": "http://127.0.0.1:11434"}],
         "models": [
           {"provider": "acme", "model": "zero", "toolMode": "JSON"},
           {"provider": "gemini", "model": "g-1", "prices": [{"input": 0.5, "output": 3.0, "cacheRead": 0.05}]}
         ],
         "freeModels": [],
         "unknownPrice": {"input": 5.0, "output": 25.0, "cacheRead": 0.5, "cacheWrite": 6.25},
         "copilot": {"pricesCopiedOn": "2026-11-17", "pricesSource": "https://docs.github.com/",
           "models": [
             {"name": "Claude Sonnet 5.5", "id": "claude-sonnet-5.5", "prices": [{"input": 2.0, "output": 10.0}]},
             {"name": "GPT-6.1 Terra", "prices": [{"input": 1.0, "output": 5.0, "tiers": [{"aboveInputTokens": 272000, "input": 2.0, "output": 7.5}]}]}
           ],
           "lightweight": ["GPT-6 Luna"], "versatile": ["GPT-6.1 Terra"],
           "runtimeHeaders": {"Editor-Version": "vscode/1.107.0"}, "ideHeaders": {"Editor-Version": "vscode/1.107.0"}}}
    """.trimIndent()

    private val catalog = CatalogLoader.load(providers, overrides)

    @Test
    fun excludedProvidersAndChoicesDisappear() {
        assertNull(catalog.provider("gone"))
        assertEquals(listOf("acme-api-key"), catalog.provider("acme")!!.authChoices.map { it.choiceId })
        assertEquals(listOf("acme", "cloud", "gemini", "github-copilot"), catalog.providers.map { it.id })
        assertEquals("e6113549fe8800b4c736c5e8a131b47f2e015143", catalog.openclawCommit)
        assertEquals(LocalServerKind.OLLAMA, catalog.localServers.single().kind)
    }

    @Test
    fun choicesGetTheirSettingsAndKestrelsOwnChoicesAreAdded() {
        val key = catalog.choice("acme", "acme-api-key")!!
        assertEquals("Acme key", key.label)
        assertEquals(AuthMethod.API_KEY, key.settings!!.method)
        assertTrue(key.offered)
        assertFalse(catalog.choice("gemini", "gemini-api-key")!!.offered)

        val settings = catalog.choice("cloud", "cloud-keys")!!.let { assertEquals("kestrel", it.method); it.settings!! }
        assertEquals(KeyHeader.BEARER, settings.keyHeader)
        assertEquals(listOf("oauth.cloud.test"), settings.refreshHosts)
    }

    @Test
    fun correctionsApplyToTheSyncedRows() {
        val acme = catalog.provider("acme")!!
        assertEquals(ToolMode.JSON, acme.models.single { it.id == "zero" }.toolMode)
        assertEquals(ToolMode.JSON, acme.models.single { it.id == "plain" }.toolMode)
        assertEquals(ToolMode.NATIVE, acme.models.single { it.id == "fast" }.toolMode)
        assertEquals(listOf(Price(1.0, 2.0)), acme.models.single { it.id == "fast" }.prices)
        assertEquals(listOf("api.acme.test", "files.acme.test", "api2.acme.test"), acme.hosts)
        assertEquals("https://eu.acme.test/v1", acme.regions.single().baseUrl)
        assertEquals(Discovery.REFRESHABLE, acme.discovery)
    }

    @Test
    fun anAddedProviderCopiesItsRowsAfterThePricesWithTheSourcesStyle() {
        val cloud = catalog.provider("cloud")!!

        assertEquals("gemini", cloud.plugin)
        assertEquals(RequestStyle.VERTEX, cloud.style)
        assertEquals(emptyList<String>(), cloud.hosts)
        assertEquals(listOf("claude-x", "g-1"), cloud.models.map { it.id })
        val gemini = cloud.models.single { it.id == "g-1" }
        assertEquals(RequestStyle.GEMINI, gemini.style)
        assertEquals(listOf(Price(0.5, 3.0, cacheRead = 0.05)), gemini.prices)
        assertEquals(setOf(InputKind.TEXT, InputKind.IMAGE), gemini.input)
        assertNull(gemini.baseUrl)
        assertEquals("claude-x", cloud.defaultModel)
        assertEquals("g-1", cloud.utilityModel)
        assertEquals(RequestStyle.ANTHROPIC, cloud.models.single { it.id == "claude-x" }.style)
    }

    @Test
    fun copilotsRowsGetTheOverridesPricesByIdOrByNameAndNoOthers() {
        val rows = catalog.provider("github-copilot")!!.models.associateBy { it.id }

        assertEquals(listOf(Price(2.0, 10.0)), rows.getValue("claude-sonnet-5.5").prices)
        assertEquals(listOf(Price(1.0, 5.0, tiers = listOf(PriceTier(272_000, 2.0, 7.5)))), rows.getValue("gpt-6.1-terra").prices)
        assertEquals(emptyList<Price>(), rows.getValue("unlisted").prices)
    }

    @Test
    fun aCopilotModelIsMatchedByIdThenByItsNormalisedIdOrName() {
        val copilot = catalog.overrides.copilot

        assertEquals("Claude Sonnet 5.5", CatalogLoader.copilotPrice(copilot, "claude-sonnet-5.5", null)?.name)
        assertEquals("GPT-6.1 Terra", CatalogLoader.copilotPrice(copilot, "gpt-6.1-terra", null)?.name)
        assertEquals("GPT-6.1 Terra", CatalogLoader.copilotPrice(copilot, "terra-2026", "GPT 6.1_Terra")?.name)
        assertNull(CatalogLoader.copilotPrice(copilot, "gpt-6-luna", "GPT-6 Luna"))
        assertEquals("gpt6.1terra", CatalogLoader.normalise("GPT-6.1 Terra"))
    }

    @Test
    fun settingsForAnExcludedChoiceAreRefusedByTheChoicesId() {
        val clash = overrides.replace(
            """{"provider": "acme", "choiceId": "acme-api-key", "method": "API_KEY", "label": "Acme key"},""",
            """{"provider": "acme", "choiceId": "acme-cli", "method": "API_KEY", "baseUrl": "https://secret.acme.test/v1"},""",
        )
        assertTrue(clash != overrides)

        val error = assertThrows(IllegalArgumentException::class.java) { CatalogLoader.load(providers, clash) }

        assertEquals("The choice acme-cli is excluded and has settings", error.message)
    }

    @Test
    fun hostOfReadsTheRealHost() {
        assertEquals("api.acme.test", CatalogLoader.hostOf("https://user:pass@API.acme.test/v1"))
        assertEquals("api.acme.test", CatalogLoader.hostOf("https://api.acme.test#section"))
        assertEquals("::1", CatalogLoader.hostOf("http://[::1]:8080/v1"))
        assertNull(CatalogLoader.hostOf("not a url"))
        assertNull(CatalogLoader.hostOf(null))
    }
}
