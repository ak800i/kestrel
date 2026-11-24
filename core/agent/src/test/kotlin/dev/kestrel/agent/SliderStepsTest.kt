package dev.kestrel.agent

import dev.kestrel.agent.port.ScreenAction
import dev.kestrel.agent.port.ConfirmEnd
import dev.kestrel.providers.profiles.ModelRole
import dev.kestrel.agent.testing.ScreenBuilder
import dev.kestrel.agent.testing.ScriptedModel
import dev.kestrel.agent.testing.ScriptedModel.Companion.call
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** set_slider through the loop (design D19 of sub-project 6): the action, its result line and the completion check. */
class SliderStepsTest {
    private val page = ScreenBuilder.screen("com.android.settings", "Settings") {
        text("Brightness level", 100, viewId = "action_bar_title")
        slider("Display brightness", 95, 600, viewId = "slider")
    }

    private fun slide(percent: Int) =
        call("set_slider", "Display brightness", null, null, "percent" to percent, "reason" to "set the brightness", "category" to "ordinary_settings")

    private fun finish(vararg more: Pair<String, Any?>) = call(
        "finish", null, null, null, "summary" to "The brightness is at 30 percent.",
        "evidence" to listOf(mapOf<String, Any?>("target" to mapOf("label" to "Display brightness")) + more),
    )

    @Test
    fun theSliderMovesAndItsResultSaysWhatItShowsAndTheFinishChecksIt() = runTest {
        val harness = Harness(page, listOf(slide(30), finish()))

        val report = harness.run("set the brightness to 30 percent")

        assertEquals(EndReason.FINISHED, report.end)
        assertEquals(30, (harness.screen.actions.single() as ScreenAction.SetSlider).percent)
        assertTrue(harness.requestText(1), harness.requestText(1).contains("Result: set \"Display brightness\" (SeekBar) to 30 % → it shows 30 %."))
    }

    @Test
    fun aSliderWithFewPositionsThatStopsAtTheNearestOnePassesTheFinishWithoutAPercent() = runTest {
        val harness = Harness(page, listOf(slide(30), finish()))
        // A slider of 7 whole-number positions puts 30 % at 33 %, which ScreenActions.setSlider counts as set.
        harness.screen.on({ action, _ -> action is ScreenAction.SetSlider }) { screen ->
            screen.copy(elements = screen.elements.map { if (it.slider) it.copy(sliderPercent = 33) else it })
        }

        val report = harness.run("set the brightness to 30 percent")

        assertEquals(EndReason.FINISHED, report.end)
        assertTrue(harness.requestText(1), harness.requestText(1).contains("to 30 % → it shows 33 %."))
    }

    @Test
    fun threeSlidersThatMoveAreProgressSoTheTaskNeitherSwitchesModelsNorAsksWhetherItIsStuck() = runTest {
        val sound = ScreenBuilder.screen("com.android.settings", "Settings") {
            text("Sound", 100, viewId = "action_bar_title")
            slider("Media volume", 95, 400)
            slider("Ring volume", 95, 600)
            slider("Alarm volume", 95, 800)
        }
        fun slide(label: String) = call("set_slider", label, null, null, "percent" to 50, "reason" to "r", "category" to "ordinary_settings")
        val done = call(
            "finish", null, null, null, "summary" to "The volumes are at 50 percent.",
            "evidence" to listOf(mapOf("target" to mapOf("label" to "Alarm volume"))),
        )
        val harness = Harness(sound, listOf(slide("Media volume"), slide("Ring volume"), slide("Alarm volume"), done))

        val report = harness.run("set the media, ring and alarm volume to 50 percent")

        assertEquals(EndReason.FINISHED, report.end)
        assertEquals(List(4) { ModelRole.LIGHTWEIGHT }, harness.model.roles)
        assertTrue(harness.owner.asked.isEmpty())
    }

    @Test
    fun aConfirmedSliderInTheCategoryDeletingDeletesNothingInTheReport() = runTest {
        val harness = Harness(page, listOf(
            call("set_slider", "Display brightness", null, null, "percent" to 30, "reason" to "r", "category" to "deleting"),
            finish(),
        ))
        harness.owner.confirms += ConfirmEnd.YES

        val report = harness.run("set the brightness to 30 percent")

        assertEquals(EndReason.FINISHED, report.end)
        assertEquals(1, harness.owner.confirmations.size)
        assertEquals(listOf("set \"Display brightness\" to 30 %"), report.ledger)
    }

    @Test
    fun aFinishThatCitesAnotherPercentThanTheSliderShowsFails() = runTest {
        val harness = Harness(page, listOf(finish("percent" to 30), call("fail", null, null, null, "reason" to "Stop.")))

        harness.run("set the brightness to 30 percent")

        assertTrue(harness.requestText(1), harness.requestText(1).contains("shows 95 %"))
        assertTrue(harness.screen.actions.isEmpty())
    }

    @Test
    fun aScriptsTargetMayNameAPartOfALabel() = runTest {
        val byPart = ScriptedModel.json(
            mapOf("tool" to "set_slider", "target" to mapOf("contains" to "display BRIGHT"), "arguments" to mapOf("percent" to 40, "reason" to "r", "category" to "ordinary_settings")),
        ) as JsonObject
        val harness = Harness(page, listOf(byPart, call("fail", null, null, null, "reason" to "Stop.")))

        harness.run("set the brightness to 40 percent")

        assertEquals(40, (harness.screen.actions.single() as ScreenAction.SetSlider).percent)
    }
}
