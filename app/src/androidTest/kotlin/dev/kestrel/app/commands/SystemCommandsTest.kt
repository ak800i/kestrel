package dev.kestrel.app.commands

import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.commands.Outcome
import dev.kestrel.screen.act.GlobalAction
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The global actions, "open apps", "show previous app" and Circle to Search (design D9), on the emulator's own apps.
 * Like every command test, they run only through scripts\command-test.ps1, which puts back the lock-screen setting and
 * deletes the screenshots and the test app's notification.
 */
@RunWith(AndroidJUnit4::class)
class SystemCommandsTest {
    // Before ScreenServiceRule, as in plan 3A's classes: a run without kestrelCommands=true skips each test before the rule
    // switches the accessibility services, and before @Before and @After run.
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
    fun goBackClosesTheDialogAndHomeShowsTheHomeScreen() = runBlocking<Unit> {
        screen.open("CommandsActivity", "Commands fixture")
        assertEquals(Outcome.DONE, CommandTests.say("tap Open dialog").outcome)
        screen.awaitElement { it.label == "Draft" }

        assertEquals(Outcome.DONE, CommandTests.say("go back").outcome)
        assertTrue(VoiceTests.waitUntil(5_000) { runBlocking { screen.session.snapshots.current().snapshot.elements.none { it.label == "Draft" } } })
        assertEquals(Outcome.DONE, CommandTests.say("home").outcome)

        assertTrue("The home screen is not in front", PhoneTests.awaitActiveApp(screen.session, PhoneTests.homePackage()))
    }

    @Test
    fun theShadeQuickSettingsAndTheRecentAppsOpen() = runBlocking<Unit> {
        screen.shell("pm grant $FIXTURES android.permission.POST_NOTIFICATIONS")
        screen.shell("am start -W -n $FIXTURES/.NotificationActivity")
        try {
            assertEquals(Outcome.DONE, CommandTests.say("show notifications").outcome)
            screen.awaitElement { it.packageName == PhoneTests.SYSTEM_UI && it.label == "Fixture reminder" }
            screen.session.actions.global(GlobalAction.DISMISS_SHADE)

            assertEquals(Outcome.DONE, CommandTests.say("show quick settings").outcome)
            screen.awaitElement { it.packageName == PhoneTests.SYSTEM_UI && it.viewId?.contains(":id/qs_tile") == true }
            screen.session.actions.global(GlobalAction.DISMISS_SHADE)

            screen.open("CommandsActivity", "Commands fixture")
            assertEquals(Outcome.DONE, CommandTests.say("show recent apps").outcome)
            // The home app shows the recent apps.
            assertTrue("The recent apps did not show", PhoneTests.awaitActiveApp(screen.session, PhoneTests.homePackage()))
        } finally {
            screen.shell("am start -W -n $FIXTURES/.NotificationActivity --ez cancel true")
            screen.session.actions.global(GlobalAction.DISMISS_SHADE)
        }
    }

    @Test
    fun lockScreenTurnsTheScreenOffAndTheCommandStillEnds() {
        // kestrel-api37 has no lock screen, so the test turns on the swipe lock screen, as ListeningServiceTest does.
        val noLockScreen = screen.shell("cmd lock_settings get-disabled").trim() == "true"
        if (noLockScreen) screen.shell("cmd lock_settings set-disabled false")
        try {
            assertEquals(Outcome.DONE, CommandTests.say("lock screen").outcome)

            val power = context.getSystemService(PowerManager::class.java)
            assertTrue("The screen is still on", VoiceTests.waitUntil(5_000) { !power.isInteractive })
        } finally {
            screen.shell("input keyevent KEYCODE_WAKEUP")
            screen.shell("wm dismiss-keyguard")
            if (noLockScreen) screen.shell("cmd lock_settings set-disabled true")
        }
    }

    @Test
    fun turnOffDeviceShowsThePowerMenu() = runBlocking<Unit> {
        try {
            assertEquals(Outcome.DONE, CommandTests.say("turn off device").outcome)

            screen.awaitElement { it.label.equals("Restart", ignoreCase = true) || it.label.equals("Power off", ignoreCase = true) }
        } finally {
            screen.session.actions.global(GlobalAction.BACK)
        }
    }

    @Test
    fun aScreenshotIsTakenWithoutTheFeedbackBar() {
        screen.open("CommandsActivity", "Commands fixture")
        val before = PhoneTests.largestRowId(PhoneTests.IMAGES)
        try {
            assertEquals(Outcome.DONE, CommandTests.say("take a screenshot").outcome)

            assertNull(screen.session.feedbackText)
            assertTrue("No screenshot was saved", VoiceTests.waitUntil(10_000) { PhoneTests.largestRowId(PhoneTests.IMAGES) > before })
        } finally {
            PhoneTests.deleteRowsAfter(PhoneTests.IMAGES, before)
        }
    }

    @Test
    fun openAppsShowsTheListOfAllApps() = runBlocking<Unit> {
        screen.shell("input keyevent KEYCODE_HOME")
        assertTrue(PhoneTests.awaitActiveApp(screen.session, PhoneTests.homePackage()))

        assertEquals(Outcome.DONE, CommandTests.say("open apps").outcome)

        // The home screen shows neither app; the list of all apps shows both.
        val home = PhoneTests.homePackage()
        screen.awaitElement { it.packageName == home && it.label == "Clock" }
        screen.awaitElement { it.packageName == home && it.label == "Contacts" }
    }

    @Test
    fun showPreviousAppBringsBackTheAppBeforeTheCurrentOne() {
        screen.open("CommandsActivity", "Commands fixture")
        screen.shell("am start -W -a android.settings.SETTINGS")
        assertTrue(PhoneTests.awaitActiveApp(screen.session, PhoneTests.SETTINGS))

        assertEquals(Outcome.DONE, CommandTests.say("show previous app").outcome)

        assertTrue("The fixture app did not come back", PhoneTests.awaitActiveApp(screen.session, FIXTURES))
    }

    @Test
    fun theOverviewsAppIsTheRecentsComponentThatAndroidReports() {
        // "mRecentsComponent=ComponentInfo{package/class}" names a package, no window content.
        val reported = Regex("mRecentsComponent=ComponentInfo\\{([^/}]+)/").find(screen.shell("dumpsys activity recents"))?.groupValues?.get(1)

        assertEquals(checkNotNull(reported) { "dumpsys names no recents component" }, screen.session.system.recentsApp())
    }

    @Test
    fun circleToSearchIsNotAvailableOnTheEmulator() {
        assertEquals(Outcome.FAILED, CommandTests.say("circle to search").outcome)

        CommandTests.awaitSaid(PhoneCommandTexts.NO_CIRCLE_TO_SEARCH)
    }
}
