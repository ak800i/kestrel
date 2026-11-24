package dev.kestrel.agent.loop

import dev.kestrel.agent.Screens
import dev.kestrel.agent.testing.ScreenBuilder
import dev.kestrel.agent.tools.Tools
import dev.kestrel.screen.model.ScreenSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressTest {
    private val signature = ScreenSignature.of(Screens.chats())

    @Test
    fun aChangeOfScreenOrTargetIsProgressAndResetsTheCount() {
        val progress = Progress()
        assertFalse(progress.record(Tools.TAP, Outcome.NothingChanged))
        assertFalse(progress.record(Tools.TAP, Outcome.Failed("the element is gone")))
        assertEquals(2, progress.withoutProgress)

        assertTrue(progress.record(Tools.TAP, Outcome.AnotherScreen("Ana")))
        assertEquals(0, progress.withoutProgress)
        assertTrue(progress.record(Tools.SCROLL, Outcome.TargetChanged))
    }

    @Test
    fun aChangeElsewhereCountsOnlyTheFirstTimeOnItsToolTargetAndScreen() {
        val progress = Progress()
        assertTrue(progress.record(Tools.TAP, Outcome.ChangedElsewhere, "plus", signature))
        assertFalse(progress.record(Tools.TAP, Outcome.ChangedElsewhere, "plus", signature))
        assertTrue(progress.record(Tools.TAP, Outcome.ChangedElsewhere, "minus", signature))
    }

    @Test
    fun aDataResultCountsOnlyWhenItDiffersFromEveryEarlierResultOfTheSameTool() {
        val progress = Progress()
        assertTrue(progress.record(Tools.LIST_SMS, Outcome.DataOk(2), dataText = "a\nb"))
        assertFalse(progress.record(Tools.LIST_SMS, Outcome.DataOk(2), dataText = "a\nb"))
        assertTrue(progress.record(Tools.LIST_CALLS, Outcome.DataOk(2), dataText = "a\nb"))
        assertTrue(progress.record(Tools.LIST_SMS, Outcome.DataEmpty, dataText = "No items."))
    }

    @Test
    fun answersAddedElementsAndTheOwnersStepAreProgressAndRefusalsAreNot() {
        val progress = Progress()
        assertTrue(progress.record(Tools.ASK_USER, Outcome.Answered))
        assertFalse(progress.record(Tools.ASK_USER, Outcome.NoAnswer))
        assertFalse(progress.record(Tools.TAP, Outcome.Refused("blocked")))
        assertFalse(progress.record(Tools.TAP, Outcome.CheckFailed("the field changed")))
        assertFalse(progress.record(Tools.TAP, Outcome.Uncertain))
        assertTrue(progress.record(Tools.LOOK_CLOSER, null, added = true))
        assertTrue(progress.record(Tools.TAP, null, ownerDidIt = true))
    }

    @Test
    fun aSliderThatMovedIsProgressAndOneThatStayedIsNot() {
        val progress = Progress()
        assertFalse(progress.record(Tools.SET_SLIDER, Outcome.SliderAt(50, moved = false)))
        assertTrue(progress.record(Tools.SET_SLIDER, Outcome.SliderAt(50, moved = true)))
    }

    @Test
    fun aScrollWithoutAToolbarTitleChangesTheTargetNotTheScreen() {
        // Review A3-M1: on a screen without a toolbar title, rule 3 would pick the first visible row, which a scroll
        // changes; D6 compares titles without rule 3, so the scroll is progress on the target.
        fun page(vararg rows: String) = ScreenBuilder.screen("com.example.list") {
            val list = list(null, 0, 2400, viewId = "list")
            rows.forEachIndexed { index, row -> text(row, 10 + index * 130, parent = list) }
        }
        val outcome = Changes.outcome(page("Row 1", "Row 2", "Row 3"), page("Row 4", "Row 5", "Row 6"), page("Row 1").elements[0].key)

        assertEquals(Outcome.TargetChanged, outcome)
        assertTrue(Progress().record(Tools.SCROLL, outcome))
    }
}
