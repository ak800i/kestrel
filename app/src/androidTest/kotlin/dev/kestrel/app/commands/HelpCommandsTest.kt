package dev.kestrel.app.commands

import androidx.annotation.StringRes
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.R
import dev.kestrel.app.listen.ListeningTexts
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.screen.ScreenServiceRule.Companion.KESTREL
import dev.kestrel.commands.Outcome
import dev.kestrel.screen.model.ScreenElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * "What can I say?" and its kin open Kestrel's settings app on the page "Instant commands" and say a short summary
 * (design spec, Appendix A, "Help"). Like every command test, they run only through scripts\command-test.ps1.
 */
class HelpCommandsTest {
    // Before ScreenServiceRule: without kestrelCommands=true the test skips before it changes anything, and its @After
    // does not run either, so a skipped test never presses Home.
    @get:Rule(order = 0)
    val only = CommandTests.only

    @get:Rule(order = 1)
    val screen = ScreenServiceRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun start() = CommandTests.start()

    @After
    fun stop() {
        CommandTests.stop()
        screen.shell("input keyevent KEYCODE_HOME")
    }

    @Test
    fun whatCanISayOpensTheListOfCommandsAndSaysASummary() = runBlocking<Unit> {
        screen.open("CommandsActivity", "Commands fixture")

        assertEquals(Outcome.DONE, CommandTests.say("what can I say").outcome)

        awaitKestrel(R.string.commands_list)
        CommandTests.awaitSaid(CommandTexts.HELP)
        // Help is not repeatable, and turning Kestrel off before the test forgot any earlier command. "Again" with nothing to
        // repeat ends no command, so the test sends it and waits for the bar.
        CommandTests.send("again")
        withTimeout(5_000) { while (screen.session.feedbackText != ListeningTexts.NOTHING_TO_REPEAT) delay(50) }
    }

    @Test
    fun theOtherHelpPhrasesOpenTheListAgainWhileTheSettingsAppShowsAnotherPage() = runBlocking<Unit> {
        screen.open("CommandsActivity", "Commands fixture")
        assertEquals(Outcome.DONE, CommandTests.say("show all commands").outcome)
        awaitKestrel(R.string.commands_list)

        // Back to the settings app's home page, which stays in front; MainActivity is singleTop.
        screen.session.actions.tap(awaitKestrel(R.string.settings_back).key)
        awaitKestrel(R.string.settings_checklist)
        assertEquals(Outcome.DONE, CommandTests.say("open tutorial").outcome)
        awaitKestrel(R.string.commands_list)

        screen.session.actions.tap(awaitKestrel(R.string.settings_back).key)
        awaitKestrel(R.string.settings_checklist)
        assertEquals(Outcome.DONE, CommandTests.say("show commands").outcome)
        awaitKestrel(R.string.commands_list)
    }

    /**
     * Waits for an element of Kestrel's settings app whose text starts with the text [id]. A page entry's card joins its
     * title and its summary into one text.
     */
    private suspend fun awaitKestrel(@StringRes id: Int): ScreenElement =
        screen.awaitElement { it.packageName == KESTREL && it.label?.startsWith(context.getString(id)) == true }
}
