package dev.kestrel.app.agent

import dev.kestrel.agent.EndReason
import dev.kestrel.agent.port.ModelPort
import dev.kestrel.agent.testing.ScriptedModel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** How a round's model is chosen (design D28 of sub-project 5; plan 7D). */
class RoundModelsTest {
    private val profileModel = ScriptedModel(emptyList())
    private val asked = mutableListOf<String?>()
    private var live = false
    private var profile: ModelPort? = profileModel

    private fun models(debuggable: Boolean) = RoundModels(debuggable, { live }) { lightweight ->
        asked += lightweight
        profile
    }

    @Test
    fun aReleaseBuildUsesTheDefaultProfileOrSaysThatNoneIsSetUp() = runTest {
        val release = models(debuggable = false)

        assertSame(profileModel, (release.choose(mapOf(RoundModels.MODEL to "gpt-5-mini")) as RoundModels.Choice.Model).port)
        assertEquals(listOf<String?>(null), asked)

        profile = null
        assertEquals(RoundModels.Choice.Refused(AgentTexts.NO_PROFILE, EndReason.NO_PROFILE), release.choose(emptyMap()))
    }

    @Test
    fun aDebugBuildRunsOnlyWithAScriptOrInALiveSitting() = runTest {
        val debug = models(debuggable = true)
        val refused = RoundModels.Choice.Refused(AgentTexts.couldNotStart(AgentTexts.TEST_BUILD), EndReason.NO_PROFILE)

        assertEquals(refused, debug.choose(emptyMap()))
        // The hook's extra live counts only while a live sitting is open.
        assertEquals(refused, debug.choose(mapOf(RoundModels.LIVE to "true")))

        live = true
        val chosen = debug.choose(mapOf(RoundModels.LIVE to "true", RoundModels.MODEL to "gpt-5-mini")) as RoundModels.Choice.Model
        assertSame(profileModel, chosen.port)
        assertEquals(listOf<String?>("gpt-5-mini"), asked)

        val script = ScriptedModel(emptyList())
        debug.modelOverride = script
        assertSame(script, (debug.choose(emptyMap()) as RoundModels.Choice.Model).port)
    }
}
