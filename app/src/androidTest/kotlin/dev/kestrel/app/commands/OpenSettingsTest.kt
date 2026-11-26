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
import dev.kestrel.app.listen.SettingsOpener
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.screen.ScreenServiceRule.Companion.KESTREL
import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.screen.model.ScreenElement
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * "Kestrel, open settings" with Kestrel's accessibility service on (design spec, sections 8.6 and 15), which starts the
 * settings app from the background, over Android's screen that the setup checklist opened above the conversation screen
 * and the settings app. It runs only through scripts\command-test.ps1, as the command tests do, because it
 * needs the accessibility service and that script's restores.
 */
class OpenSettingsTest {
    // Before ScreenServiceRule, as in every command test, so that a skipped test changes nothing.
    @get:Rule(order = 0)
    val only = CommandTests.only

    @get:Rule(order = 1)
    val screen = ScreenServiceRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val timeline = CommandTests.graph.timeline

    @Before
    fun start() = CommandTests.start()

    @After
    fun stop() {
        CommandTests.stop()
        screen.shell("input keyevent KEYCODE_HOME")
    }

    @Test
    fun openSettingsShowsTheSettingsOverAnotherAppsScreenInKestrelsTaskAndClosesNothing() = runBlocking<Unit> {
        // A task that the launcher's own intent started, as when the owner opened Kestrel from the launcher: since plan 7F
        // it opens the conversation screen, whose Settings open the settings app in the same task.
        val launcher = checkNotNull(context.packageManager.getLaunchIntentForPackage(KESTREL)?.component) { "Kestrel has no launcher activity" }
        screen.shell(
            "am start -W --activity-clear-task -a android.intent.action.MAIN -c android.intent.category.LAUNCHER " +
                "-n ${launcher.flattenToShortString()}",
        )
        val settings = awaitConversationScreen().bounds
        screen.shell("input tap ${settings.centerX} ${settings.centerY}")
        awaitKestrel(R.string.settings_checklist)
        // The setup checklist starts Android's screens into Kestrel's task, so one of them now covers the settings app
        // there.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .single { it is MainActivity }
                .startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        awaitAndroidSettings()
        assertTrue("Kestrel's settings are still in front", VoiceTests.waitUntil(5_000) { !timeline.settingsInFront })

        val resumes = timeline.settingsResumes.value
        val askedBefore = timeline.settingsAsked
        CommandTests.graph.listening.send(Event.Heard("Kestrel, open settings", named = true))

        // An intent equal to the task's first one would only bring the task to the front, with Android's screen on top.
        assertTrue(
            "Kestrel's settings did not come to the front",
            VoiceTests.waitUntil(15_000) { timeline.settingsResumes.value > resumes && timeline.settingsInFront },
        )
        val asked = timeline.settingsAsked
        val resumed = timeline.settingsResumes.value
        assertTrue("Kestrel did not ask for its settings in this run", asked > askedBefore)
        assertTrue(
            "Kestrel's settings came ${resumed - asked} ms after Kestrel asked for them, after its wait",
            resumed - asked < SettingsOpener.WAIT_MILLIS,
        )
        assertFalse("Kestrel said that Android blocked its settings", ListeningTexts.SETTINGS_BLOCKED in CommandTests.spoken)
        awaitKestrel(R.string.settings_checklist)

        // Android's screen is still there under the new settings, the first settings under it, and the conversation screen
        // at the root of the task.
        screen.shell("input keyevent KEYCODE_BACK")
        awaitAndroidSettings()
        screen.shell("input keyevent KEYCODE_BACK")
        awaitKestrel(R.string.settings_checklist)
        screen.shell("input keyevent KEYCODE_BACK")
        awaitConversationScreen()
    }

    // The conversation screen's Settings button.
    private suspend fun awaitConversationScreen(): ScreenElement =
        screen.awaitElement(15_000) { it.packageName == KESTREL && it.label == context.getString(R.string.conversation_settings) }

    private suspend fun awaitAndroidSettings() = screen.awaitElement(15_000) { it.packageName == "com.android.settings" }

    // Waits 15 s, as HelpCommandsTest does, because a loaded kestrel-api37 took up to 4 s to show an activity.
    private suspend fun awaitKestrel(@StringRes id: Int): ScreenElement =
        screen.awaitElement(15_000) { it.packageName == KESTREL && it.label?.startsWith(context.getString(id)) == true }
}
