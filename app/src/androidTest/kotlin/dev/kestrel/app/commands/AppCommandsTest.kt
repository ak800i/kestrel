package dev.kestrel.app.commands

import android.content.pm.LauncherApps
import android.os.UserHandle
import android.os.UserManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.commands.Outcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Open X" and "open work X" (design D9; research r21), with the emulator's Clock app in the owner's profile and in a work
 * profile that the tests make with `pm create-user --profileOf 0 --managed` and remove; one test pauses it.
 * command-test.ps1 also removes a profile that an interrupted run left.
 */
@RunWith(AndroidJUnit4::class)
class AppCommandsTest {
    // Before ScreenServiceRule, as in plan 3A's classes: a run without kestrelCommands=true skips each test before the rule
    // switches the accessibility services, and before @Before and @After run.
    @get:Rule(order = 0)
    val only = CommandTests.only

    @get:Rule(order = 1)
    val screen = ScreenServiceRule()

    private val launcher = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(LauncherApps::class.java)

    @Before
    fun start() {
        removeWorkProfiles()
        CommandTests.start()
    }

    @After
    fun stop() {
        CommandTests.stop()
        removeWorkProfiles()
        screen.shell("input keyevent KEYCODE_HOME")
    }

    @Test
    fun openStartsTheAppThatTheOwnerNames() {
        assertEquals(Outcome.DONE, CommandTests.say("open Clock").outcome)

        assertTrue("Clock is not in front", PhoneTests.awaitActiveApp(screen.session, PhoneTests.CLOCK))
    }

    @Test
    fun anAppThatIsNotThereIsSaid() {
        assertEquals(Outcome.FAILED, CommandTests.say("open Quorbex Zyntal").outcome)

        CommandTests.awaitSaid("I can't find an app called Quorbex Zyntal.")
    }

    @Test
    fun openWorkWithoutAWorkProfileSaysSo() {
        assertEquals(Outcome.FAILED, CommandTests.say("open work Clock").outcome)

        CommandTests.awaitSaid(PhoneCommandTexts.NO_WORK_PROFILE)
    }

    @Test
    fun openWorkStartsTheWorkProfilesAppAndOpenTheOwnersOne() {
        val (user, _) = createWorkProfile()

        assertEquals(Outcome.DONE, CommandTests.say("open work Clock").outcome)
        assertTrue("The work profile's Clock is not in front", VoiceTests.waitUntil(10_000) { resumed().contains("u$user ${PhoneTests.CLOCK}/") })

        assertEquals(Outcome.DONE, CommandTests.say("open Clock").outcome)
        assertTrue("The owner's Clock is not in front", VoiceTests.waitUntil(10_000) { resumed().contains("u0 ${PhoneTests.CLOCK}/") })
    }

    @Test
    fun aPausedWorkProfilesAppOpensAndKestrelSaysThatWorkAppsArePaused() {
        val (_, work) = createWorkProfile()
        val users = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(UserManager::class.java)
        // Pausing a profile needs MANAGE_USERS or MODIFY_QUIET_MODE, which only the shell holds here. The rule's UiAutomation
        // keeps the accessibility services on; Instrumentation.getUiAutomation() would connect it again without that flag
        // and turn Kestrel's service off.
        screen.automation.adoptShellPermissionIdentity()
        try {
            users.requestQuietModeEnabled(true, work)
        } finally {
            screen.automation.dropShellPermissionIdentity()
        }
        assertTrue("Android did not pause the work profile", VoiceTests.waitUntil(10_000) { users.isQuietModeEnabled(work) })

        // Android shows its own dialog to turn work apps on.
        assertEquals(Outcome.DONE, CommandTests.say("open work Clock").outcome)
        CommandTests.awaitSaid(PhoneCommandTexts.WORK_APPS_PAUSED)
    }

    // Makes a work profile, starts it, waits until the launcher API lists it next to the profiles that were there before,
    // however many there are, and returns its user ID and its handle.
    private fun createWorkProfile(): Pair<String, UserHandle> {
        val before = launcher.profiles.toSet()
        val created = screen.shell("pm create-user --profileOf 0 --managed $PROFILE")
        val user = Regex("created user id (\\d+)").find(created)?.groupValues?.get(1) ?: throw AssertionError("No profile: $created")
        screen.shell("am start-user -w $user")
        var work: UserHandle? = null
        val listed = VoiceTests.waitUntil(15_000) {
            work = launcher.profiles.firstOrNull { it !in before }
            work != null
        }
        assertTrue("Android does not list the profile", listed)
        return user to checkNotNull(work)
    }

    // The resumed activity, with its user, as "u10 com.google.android.deskclock/...". It names no window content.
    private fun resumed(): String = screen.shell("dumpsys activity activities").lineSequence().firstOrNull { "topResumedActivity" in it }.orEmpty()

    private fun removeWorkProfiles() {
        for (match in Regex("UserInfo\\{(\\d+):$PROFILE:").findAll(screen.shell("pm list users"))) {
            screen.shell("pm remove-user ${match.groupValues[1]}")
        }
    }

    private companion object {
        const val PROFILE = "KestrelTest"
    }
}
