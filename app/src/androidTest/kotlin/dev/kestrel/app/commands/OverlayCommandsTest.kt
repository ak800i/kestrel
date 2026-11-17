package dev.kestrel.app.commands

import dev.kestrel.app.SettingKeys
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.commands.Outcome
import dev.kestrel.screen.overlay.NumbersMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Numbers, labels, the grid, "What is N?" and "Show commands for X" by voice (design spec, Appendix A, "Help" and
 * "Grid"), through the same path as speech. Like every command test, they run only through scripts\command-test.ps1.
 */
class OverlayCommandsTest {
    // Before ScreenServiceRule, as in RefusedReplyTest: a run without kestrelCommands=true skips each test before the rule
    // switches the accessibility services, and before @Before and @After run.
    @get:Rule(order = 0)
    val only = CommandTests.only

    @get:Rule(order = 1)
    val screen = ScreenServiceRule()

    @Before
    fun start() = CommandTests.start()

    @After
    fun stop() = CommandTests.stop()

    @Test
    fun numbersAndLabelsShowAndHide() = runBlocking<Unit> {
        screen.open("CommandsActivity", "Commands fixture")

        assertEquals(Outcome.DONE, CommandTests.say("show numbers").outcome)
        assertTrue(screen.session.numberBadges.isNotEmpty())
        assertEquals(Outcome.DONE, CommandTests.say("hide numbers").outcome)
        assertTrue(screen.session.numberBadges.isEmpty())
        assertEquals(Outcome.DONE, CommandTests.say("show labels").outcome)
        assertTrue(screen.session.labelsShowing)
        assertEquals(Outcome.DONE, CommandTests.say("hide labels").outcome)
        assertFalse(screen.session.labelsShowing)
    }

    @Test
    fun whatIsANumberSaysTheNameAndTheKindOfItsElement() = runBlocking<Unit> {
        screen.open("CommandsActivity", "Commands fixture")
        assertEquals(Outcome.DONE, CommandTests.say("show numbers").outcome)
        val save = badge(screen.awaitElement { it.label == "Save" }.key)
        val subscribe = badge(screen.awaitElement { it.label == "Subscribe" && it.checkable }.key)

        assertEquals(Outcome.DONE, CommandTests.say("what is $save").outcome)
        CommandTests.awaitSaid("Number $save is Save, a button.")
        assertEquals(Outcome.DONE, CommandTests.say("what is $subscribe").outcome)
        CommandTests.awaitSaid("Number $subscribe is Subscribe, a check box, off.")
        // A number that the screen does not show goes to the bar, as with "tap 99", and Kestrel does not say it.
        assertEquals(Outcome.FAILED, CommandTests.say("what is 99").outcome)
        awaitBar(CommandTexts.noNumber(99))
        assertFalse(CommandTexts.noNumber(99) in CommandTests.spoken)
    }

    @Test
    fun showCommandsForSaysWhatAnElementAllows() = runBlocking<Unit> {
        screen.open("CommandsActivity", "Commands fixture")

        assertEquals(Outcome.DONE, CommandTests.say("show commands for Subscribe").outcome)
        CommandTests.awaitSaid("You can say tap, check or uncheck.")
        assertEquals(Outcome.DONE, CommandTests.say("show commands for Counter").outcome)
        CommandTests.awaitSaid("You can say tap or long press.")
    }

    @Test
    fun theGridTapsAndMakesGesturesOnItsSquares() = runBlocking<Unit> {
        screen.open("GesturesActivity", "Gestures fixture")

        // Without a grid, "more squares" and "fewer squares" ask for one.
        for (squares in listOf("more squares", "fewer squares")) {
            val said = CommandTests.spoken.size
            assertEquals(Outcome.FAILED, CommandTests.say(squares).outcome)
            withTimeout(5_000) {
                while (CommandTests.spoken.size == said || CommandTests.spoken.last() != CommandTexts.SHOW_GRID_FIRST) delay(50)
            }
        }
        assertEquals(Outcome.DONE, CommandTests.say("show grid").outcome)
        assertEquals(4, screen.session.grid?.columns)
        assertEquals(Outcome.DONE, CommandTests.say("more squares").outcome)
        assertEquals(6, screen.session.grid?.columns)
        assertEquals(Outcome.DONE, CommandTests.say("fewer squares").outcome)
        assertEquals(4, screen.session.grid?.columns)
        // On kestrel-api37's screen of 1080 by 2400 pixels, the grid has 4 columns and 9 rows: square 18 lies on the pad,
        // and square 26 two rows below it.
        assertEquals(Outcome.DONE, CommandTests.say("tap 18").outcome)
        awaitStatus("Tap")
        assertEquals(Outcome.DONE, CommandTests.say("long press 18").outcome)
        awaitStatus("Long press")
        assertEquals(Outcome.DONE, CommandTests.say("18").outcome)
        awaitStatus("Tap")
        assertEquals(Outcome.DONE, CommandTests.say("swipe 18 up").outcome)
        awaitStatus("Swipe up")
        assertEquals(Outcome.DONE, CommandTests.say("pinch out 18").outcome)
        awaitStatus("Pinch out")
        assertEquals(CommandTexts.DRAG_WHERE, CommandTests.ask("drag 18"))
        assertEquals(Outcome.DONE, CommandTests.answer("26").outcome)
        awaitStatus("Drag down")
        assertEquals(Outcome.DONE, CommandTests.say("what is 18").outcome)
        CommandTests.awaitSaid("That's square 18 of the grid.")
        assertEquals(Outcome.DONE, CommandTests.say("hide grid").outcome)
        assertNull(screen.session.grid)
    }

    @Test
    fun theNumbersSettingPutsNumbersOnlyOnElementsWithoutAName() = runBlocking<Unit> {
        val settings = CommandTests.graph.settings
        settings.set(SettingKeys.COMMANDS, CommandOptions(numbers = NumbersMode.UNLABELED))
        try {
            withTimeout(5_000) { while (CommandTests.graph.commandOptions.value.numbers != NumbersMode.UNLABELED) delay(50) }
            screen.open("CommandsActivity", "Commands fixture")
            val seekBar = screen.awaitElement { it.viewId == "$FIXTURES:id/seek_bar" }
            val save = screen.awaitElement { it.label == "Save" }

            assertEquals(Outcome.DONE, CommandTests.say("show numbers").outcome)

            val numbered = screen.session.numberBadges.map { it.key }
            assertTrue("The seek bar, which has no name, has no number", seekBar.key in numbered)
            assertFalse("Save has a number", save.key in numbered)
        } finally {
            settings.set(SettingKeys.COMMANDS, CommandOptions())
            withTimeout(5_000) { while (CommandTests.graph.commandOptions.value != CommandOptions()) delay(50) }
        }
    }

    @Test
    fun hideFeedbackHidesAKeptTextAndLeavesTheNumbers() = runBlocking<Unit> {
        screen.open("CommandsActivity", "Commands fixture")
        val session = screen.session
        session.showNumbers()

        // In one go on the main thread, so that no text of Kestrel's own comes in between.
        val shown = withContext(Dispatchers.Main) {
            session.showFeedback("Calling", keep = true)
            session.hideFeedback()
            session.feedbackText
        }

        assertNull(shown)
        assertTrue(session.numberBadges.isNotEmpty())
    }

    private suspend fun awaitStatus(text: String) = screen.awaitElement { it.viewId == "$FIXTURES:id/status" && it.label == text }

    /** The number on the element with [key], once the numbers have followed the screen. */
    private suspend fun badge(key: String): Int = withTimeout(5_000) {
        var badge = screen.session.numberBadges.firstOrNull { it.key == key }
        while (badge == null) {
            delay(50)
            badge = screen.session.numberBadges.firstOrNull { it.key == key }
        }
        badge.number
    }

    private suspend fun awaitBar(text: String) = withTimeout(5_000) { while (screen.session.feedbackText != text) delay(50) }
}
