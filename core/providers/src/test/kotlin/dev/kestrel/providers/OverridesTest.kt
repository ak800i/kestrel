package dev.kestrel.providers

import dev.kestrel.providers.catalog.AuthMethod
import dev.kestrel.providers.catalog.Catalog
import dev.kestrel.providers.catalog.CatalogFile
import dev.kestrel.providers.catalog.CatalogLoader
import dev.kestrel.providers.catalog.ModelOverride
import dev.kestrel.providers.catalog.Overrides
import dev.kestrel.providers.chat.RequestStyle
import dev.kestrel.providers.http.Hosts
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Checks the hand-written `catalog/overrides.json` against the synced `catalog/providers.json` (design D2), so that a typo,
 * an upstream rename or a style without a provider fails the build.
 */
class OverridesTest {
    private val folder = File(System.getProperty("kestrel.catalog") ?: error("The build passes -Dkestrel.catalog"))
    private val providersJson = folder.resolve("providers.json").readText()
    private val overridesJson = folder.resolve("overrides.json").readText()
    private val json = Json { ignoreUnknownKeys = true }
    private val synced: CatalogFile = json.decodeFromString(CatalogFile.serializer(), providersJson)
    // Strict, unlike CatalogLoader: a misspelt key in the hand-written file must fail here instead of falling back to its default.
    private val overrides: Overrides = Json.decodeFromString(Overrides.serializer(), overridesJson)
    private val catalog: Catalog = CatalogLoader.load(providersJson, overridesJson)

    // The manifest headers that offered providers send, by provider, as decided (design D4, decision 6 of the design's
    // decisions). None at the pinned commit: a header that a later sync brings, such as a borrowed User-Agent, fails the
    // build until it is decided and written here, or its provider or choice is excluded. The choices' `identityHeaders` are
    // deliberately left out: they belong to a borrowed sign-in and are sent only behind its switch (design D21).
    private val allowedHeaders: Map<String, Map<String, String>> = emptyMap()

    private val syncedIds = synced.providers.map { it.id }.toSet()
    private val addedIds = overrides.providers.filter { it.id !in syncedIds }.map { it.id }.toSet()

    @Test
    fun everyNameThatTheOverridesUseExists() {
        val problems = mutableListOf<String>()
        for (exclusion in overrides.excludeProviders) {
            if (exclusion.id !in syncedIds) problems += "excluded provider ${exclusion.id}"
        }
        for (exclusion in overrides.excludeAuthChoices) {
            val choices = synced.providers.firstOrNull { it.id == exclusion.provider }?.authChoices.orEmpty()
            if (choices.none { it.choiceId == exclusion.choiceId }) problems += "excluded choice ${exclusion.provider}/${exclusion.choiceId}"
        }
        for (change in overrides.providers) {
            if (change.id !in syncedIds && change.plugin == null) problems += "added provider ${change.id} without a plugin"
            change.modelsFrom?.let { if (it !in syncedIds) problems += "modelsFrom $it" }
            val rows = catalog.provider(change.id)?.models.orEmpty().map { it.id }
            for (default in listOfNotNull(change.defaultModel, change.utilityModel)) {
                if (default !in rows) problems += "default model ${change.id}/$default"
            }
        }
        for (choice in overrides.choices) {
            if (choice.provider !in syncedIds && choice.provider !in addedIds) problems += "choice of provider ${choice.provider}"
        }
        for (model in overrides.models + overrides.freeModels.map { ModelOverride(it.provider, it.model) }) {
            val rows = synced.providers.firstOrNull { it.id == model.provider }?.models.orEmpty()
            if (rows.none { it.id == model.model }) problems += "model ${model.provider}/${model.model}"
        }
        val copilotRows = synced.providers.single { it.id == CatalogLoader.COPILOT }.models.map { it.id }
        for (price in overrides.copilot.models) {
            price.id?.let { if (it !in copilotRows) problems += "Copilot model ID $it" }
        }
        for (server in overrides.localServers) {
            if (server.id !in syncedIds) problems += "local server ${server.id}"
        }

        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun everyChoiceIsOfferedOrExcludedAndEveryOfferedProviderHasAStyle() {
        val unsettled = catalog.providers.flatMap { provider ->
            provider.authChoices.filter { it.settings == null }.map { "${provider.id}/${it.choiceId}" }
        }
        val withoutStyle = catalog.providers
            .filter { provider -> provider.authChoices.any { it.offered } }
            .filter { provider -> provider.style == null && (provider.models.isEmpty() || provider.models.any { it.style == null }) }
            .map { it.id }
        val headers = catalog.providers
            .filter { provider -> provider.authChoices.any { it.offered } && provider.headers.isNotEmpty() }
            .associate { it.id to it.headers }

        assertEquals(emptyList<String>(), unsettled)
        assertEquals(emptyList<String>(), withoutStyle)
        assertEquals(allowedHeaders, headers)
    }

    @Test
    fun everyStyleHasAnOfferedProvider() {
        val styles = catalog.providers
            .filter { provider -> provider.authChoices.any { it.offered } }
            .flatMap { provider -> listOfNotNull(provider.style) + provider.models.mapNotNull { it.style } }
            .toSet()

        assertEquals(RequestStyle.entries.toSet(), styles)
    }

    @Test
    fun borrowedAndPlanSignInsLinkToTheirProvidersTerms() {
        val signIns = catalog.providers.flatMap { it.authChoices }.mapNotNull { it.settings }
            .filter { it.borrowedIdentity != null || it.chatgptPlan }

        assertEquals(
            mapOf("copilot" to true, "minimax" to false, "xai" to false),
            signIns.filter { it.borrowedIdentity != null }.associate { it.borrowedIdentity!! to it.defaultOn }.toSortedMap(),
        )
        assertEquals(listOf("chatgpt-plan"), signIns.filter { it.chatgptPlan }.map { it.choiceId })
        for (choice in signIns) {
            assertTrue(choice.choiceId, choice.termsLinks.isNotEmpty())
            assertTrue(choice.choiceId, choice.termsLinks.all { it.url.toHttpUrl().isHttps && it.label.isNotBlank() })
        }
    }

    @Test
    fun pricesSayWhereAndWhenTheyWereCopied() {
        for (model in overrides.models.filter { it.prices != null }) {
            assertTrue("${model.provider}/${model.model}", model.pricesCopiedOn != null && model.pricesSource?.startsWith("https://") == true)
        }
        // The priced rows under providers[] are copied from OpenClaw at the pinned commit, which the synced file names.
        val pinned = folder.resolve("openclaw.lock").readText().trim()
        for (provider in overrides.providers) {
            for (row in provider.models.filter { it.prices.isNotEmpty() }) {
                assertTrue("${provider.id}/${row.id}", pinned.matches(Regex("[0-9a-f]{40}")) && synced.openclawCommit == pinned)
            }
        }
        assertEquals("2026-11-17", overrides.copilot.pricesCopiedOn)
        val names = overrides.copilot.models.map { it.name }.toSet()
        assertTrue(names.containsAll(overrides.copilot.lightweight + overrides.copilot.versatile))
    }

    @Test
    fun localServersSuggestLocalAddressesAndHaveAServerAddressChoice() {
        for (server in catalog.localServers) {
            assertTrue(server.id, Hosts.isLocal(server.defaultUrl.toHttpUrl().host))
            val methods = catalog.provider(server.id)!!.authChoices.filter { it.offered }.map { it.settings!!.method }
            assertEquals(server.id, listOf(AuthMethod.SERVER_ADDRESS), methods)
        }
    }
}
