package dev.kestrel.providers.styles

import dev.kestrel.providers.catalog.CompatFlags
import dev.kestrel.providers.catalog.ModelEntry
import dev.kestrel.providers.chat.ReasoningLevel
import dev.kestrel.providers.styles.AdapterHarness.Companion.request
import dev.kestrel.providers.styles.AdapterHarness.Companion.user
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WireTest {
    private val deepSeek = ModelEntry("deepseek-v4-flash", "DeepSeek V4 Flash", reasoning = true, thinkingLevelMap = mapOf("xhigh" to "high", "max" to "max"))
    private val deepSeekCompat = CompatFlags(supportsReasoningEffort = true)

    @Test
    fun aModelWithoutAnEffortListGetsOpenClawsListForItsFamilyOrTheGenericOne() {
        // OpenClaw's generic low, medium and high, with the efforts that the model's own map names.
        assertEquals("low", Wire.effort("minimal", deepSeek, deepSeekCompat))
        assertEquals("medium", Wire.effort("medium", deepSeek, deepSeekCompat))
        assertEquals("high", Wire.effort("xhigh", deepSeek, deepSeekCompat))
        assertEquals("max", Wire.effort("max", deepSeek, deepSeekCompat))
        // OpenClaw's GPT families: GPT-5.5 has no minimal, GPT-5 has it, and GPT-5.6 has max only outside Chat Completions.
        val gpt55 = ModelEntry("gpt-5.5-2026-08-01", "GPT-5.5", reasoning = true)
        assertEquals("low", Wire.effort("minimal", gpt55, CompatFlags()))
        assertEquals("xhigh", Wire.effort("xhigh", gpt55, CompatFlags()))
        assertEquals("minimal", Wire.effort("minimal", ModelEntry("gpt-5-mini", "GPT-5 mini", reasoning = true), CompatFlags()))
        val gpt56 = ModelEntry("gpt-5.6-sol", "GPT-5.6 Sol", reasoning = true)
        assertEquals("max", Wire.effort("max", gpt56, CompatFlags()))
        assertEquals("xhigh", Wire.effort("max", gpt56, CompatFlags(), chatCompletions = true))
    }

    @Test
    fun aLevelThatTheModelLacksMovesDownwardsFirst() {
        val model = ModelEntry("m", "M", reasoning = true)
        val levels = CompatFlags(thinkingLevels = listOf("off", "low", "high"))
        val efforts = CompatFlags(supportedReasoningEfforts = listOf("low", "high"))

        assertEquals("low", Wire.level(request(user("Hi")) { copy(reasoning = ReasoningLevel.MEDIUM) }, model, levels))
        assertEquals("low", Wire.effort("medium", model, efforts))
        // With nothing below, the lowest allowed one above.
        assertEquals("low", Wire.level(request(user("Hi")) { copy(reasoning = ReasoningLevel.MINIMAL) }, model, levels))
        assertEquals("high", Wire.effort("medium", model, CompatFlags(supportedReasoningEfforts = listOf("none", "high"))))
    }

    @Test
    fun theLevelAndTheEffortFollowTheModelsMapsAndLists() {
        val asked = request(user("Hi")) { copy(reasoning = ReasoningLevel.MINIMAL) }
        val model = ModelEntry("m", "M", reasoning = true, thinkingLevelMap = mapOf("minimal" to null, "low" to "none"))

        // A level that the map sets to null moves; one that it maps to `none` sends no effort.
        assertEquals("low", Wire.level(asked, model, CompatFlags()))
        assertNull(Wire.effort("low", model, CompatFlags()))
        assertNull(Wire.level(asked.copy(reasoning = null), model, CompatFlags()))
        assertNull(Wire.level(asked, model.copy(reasoning = false), CompatFlags()))
        assertNull(Wire.level(asked, model, CompatFlags(thinkingLevels = listOf("off"))))
        // An empty effort list sends none, and a label from the effort map is checked against the list like any effort.
        assertNull(Wire.effort("high", model, CompatFlags(supportedReasoningEfforts = emptyList())))
        assertEquals("HIGH", Wire.effort("high", model, CompatFlags(supportedReasoningEfforts = listOf("HIGH"), reasoningEffortMap = mapOf("high" to "HIGH"))))
        assertEquals("high", Wire.effort("high", model, CompatFlags(supportedReasoningEfforts = listOf("high"), reasoningEffortMap = mapOf("high" to "HIGH"))))
    }

    @Test
    fun theEffortMapAppliesBeforeTheEffortIsClampedAsInOpenClaw() {
        // Particle-1's row: OpenClaw maps MEDIUM to `high` first, and `high` is in the list.
        val particle = ModelEntry("particle-1", "Particle-1", reasoning = true)
        val compat = CompatFlags(supportedReasoningEfforts = listOf("none", "low", "high", "max"), reasoningEffortMap = mapOf("medium" to "high"))

        assertEquals("high", Wire.effort("medium", particle, compat))
        assertEquals("low", Wire.effort("low", particle, compat))
    }
}
