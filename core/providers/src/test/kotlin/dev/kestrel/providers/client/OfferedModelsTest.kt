package dev.kestrel.providers.client

import dev.kestrel.providers.catalog.InputKind
import dev.kestrel.providers.catalog.ToolMode
import dev.kestrel.providers.discovery.DiscoveredModel
import dev.kestrel.providers.signin.SignInFixtures.catalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rows that a profile offers per choice, and the defaults among them (design D12 and D13). */
class OfferedModelsTest {
    private fun listed(vararg ids: Pair<String, String>, tools: Boolean? = true) = ids.map { (id, name) -> DiscoveredModel(id, name, tools = tools) }

    @Test
    fun anApiKeyProfileOffersTheCatalogsRowsBesideItsList() {
        val rows = OfferedModels.rows(catalog, "amazon-bedrock-mantle", "mantle-api-key", listed("mistral.test-model" to "Mistral test"))

        val claudeRows = catalog.provider("amazon-bedrock-mantle")!!.models.map { it.id }
        assertTrue(claudeRows.isNotEmpty())
        assertEquals(claudeRows + "mistral.test-model", rows.map { it.id })
    }

    @Test
    fun copilotAndTheChatGptPlanOfferOnlyTheirListsWithTheCatalogsPrices() {
        val copilot = OfferedModels.rows(catalog, "github-copilot", "github-copilot", listed("claude-sonnet-5.5" to "Claude Sonnet 5.5", "copilot-new" to "New"))
        val plan = OfferedModels.rows(catalog, "openai", "chatgpt-plan", listed("gpt-6.1-sol" to "GPT-6.1 Sol", "gpt-plan-only" to "Plan only"))
        val noTools = OfferedModels.rows(catalog, "github-copilot", "github-copilot", listed("gpt-5.4-nano" to "GPT-5.4 nano", tools = false))

        assertEquals(listOf("claude-sonnet-5.5", "copilot-new"), copilot.map { it.id })
        assertTrue(copilot[0].prices.isNotEmpty())
        assertEquals(listOf("gpt-6.1-sol", "gpt-plan-only"), plan.map { it.id })
        assertEquals(ToolMode.NATIVE, plan[1].toolMode)
        assertEquals(setOf(InputKind.TEXT), plan[1].input)
        assertEquals(ToolMode.JSON, noTools.single().toolMode)
    }

    @Test
    fun copilotsDefaultsComeByNameAndTheOthersFromTheCatalogOrTheFirstRow() {
        fun copilot(vararg names: Pair<String, String>) =
            OfferedModels.defaults(catalog, "github-copilot", OfferedModels.rows(catalog, "github-copilot", "github-copilot", listed(*names)))

        assertEquals(
            ModelDefaults("gpt-6-luna", "gpt-6.1-terra"),
            copilot("claude-haiku-5.5" to "Claude Haiku 5.5", "gpt-6-luna" to "GPT-6 Luna", "gpt-6.1-terra" to "GPT-6.1 Terra"),
        )
        assertEquals(ModelDefaults("claude-haiku-5.5", "claude-haiku-5.5"), copilot("claude-haiku-5.5" to "Claude Haiku 5.5"))
        val openai = OfferedModels.rows(catalog, "openai", "openai-api-key", emptyList())
        assertEquals(ModelDefaults("gpt-6-luna", openai.first().id), OfferedModels.defaults(catalog, "openai", openai))
        assertEquals(ModelDefaults(null, null), OfferedModels.defaults(catalog, "ollama", emptyList()))
    }
}
