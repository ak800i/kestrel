package dev.kestrel.app.commands

import android.content.Intent
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.kestrel.app.MainActivity
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

    @Test
    fun aHelpPhraseShowsTheListOverAnotherAppsScreenInKestrelsTaskAndClosesNothing() = runBlocking<Unit> {
        // A task that an intent like Kestrel's own started, as "What can I say?" does when the settings app is closed.
        screen.shell("am start -W --activity-clear-task -n $KESTREL/.app.MainActivity")
        awaitKestrel(R.string.settings_checklist)
        // The setup checklist starts Android's screens into Kestrel's task, so one of them now covers the settings app
        // there, as a permission dialog does that outlived Kestrel's process.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .single { it is MainActivity }
                .startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        awaitAndroidSettings()

        assertEquals(Outcome.DONE, CommandTests.say("show commands").outcome)
        awaitKestrel(R.string.commands_list)

        // Android's screen is still there under the list: Back leads to the settings app's first page, then to it.
        screen.shell("input keyevent KEYCODE_BACK")
        awaitKestrel(R.string.settings_checklist)
        screen.shell("input keyevent KEYCODE_BACK")
        awaitAndroidSettings()
        screen.shell("input keyevent KEYCODE_BACK")
    }

    private suspend fun awaitAndroidSettings() = screen.awaitElement(15_000) { it.packageName == "com.android.settings" }

    /**
     * Waits for an element of Kestrel's settings app whose text starts with the text [id]. A page entry's card joins its
     * title and its summary into one text. It waits 15 s, because on a loaded kestrel-api37 Android took up to 4 s to show
     * an activity, close to the usual 5 s.
     */
    private suspend fun awaitKestrel(@StringRes id: Int): ScreenElement =
        screen.awaitElement(15_000) { it.packageName == KESTREL && it.label?.startsWith(context.getString(id)) == true }
}
