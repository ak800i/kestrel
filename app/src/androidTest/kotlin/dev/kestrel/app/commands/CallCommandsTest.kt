package dev.kestrel.app.commands

import android.Manifest
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.app.screen.ScreenServiceRule.Companion.KESTREL
import dev.kestrel.app.screen.TestFinger
import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.commands.Outcome
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.overlay.Numbering
import dev.kestrel.screen.overlay.NumbersMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Calls by number and by name, with the announcement and the countdown, and "copy this phone number" (design D11), on
 * the emulator's phone app, whose calls to a made-up number become active, and the owner's "yes, call" before anything that
 * may call an emergency number (design spec, section 8.7), which these tests never answer with yes in the phone app. A test
 * that fails during a call ends it. The tests grant the phone-state and contacts permissions; command-test.ps1 revokes what was not granted before, and deletes the test contacts and the new call-log
 * rows.
 */
@RunWith(AndroidJUnit4::class)
class CallCommandsTest {
    // Before ScreenServiceRule, as in plan 3A's classes: a run without kestrelCommands=true skips each test before the rule
    // switches the accessibility services, and before @Before and @After run.
    @get:Rule(order = 0)
    val only = CommandTests.only

    @get:Rule(order = 1)
    val screen = ScreenServiceRule()

    private val listening get() = CommandTests.graph.listening
    private var callLog = 0L

    @Before
    fun start() {
        // Before the start: the call state reaches Kestrel only with this permission (design spec, section 8.7).
        screen.automation.grantRuntimePermission(KESTREL, Manifest.permission.READ_PHONE_STATE)
        screen.automation.grantRuntimePermission(KESTREL, Manifest.permission.READ_CONTACTS)
        PhoneTests.removeTestContacts()
        callLog = PhoneTests.largestRowId(PhoneTests.CALL_LOG)
        CommandTests.start()
    }

    @After
    fun stop() {
        try {
            // A test that failed during a call leaves it active; without a call, the key would turn the screen off.
            if (listening.state.value.mode == Mode.IN_CALL) screen.shell("input keyevent KEYCODE_ENDCALL")
            CommandTests.stop()
        } finally {
            PhoneTests.deleteRowsAfter(PhoneTests.CALL_LOG, callLog)
            PhoneTests.removeTestContacts()
            screen.shell("input keyevent KEYCODE_HOME")
        }
    }

    @Test
    fun aNumberIsAnnouncedCountedDownAndCalled() {
        // The emulator's phone app takes no new number from a DIAL whose number its field already shows, as an earlier
        // test's call leaves it, and its call button then calls an empty number; so the field shows another number first.
        // The HOME transition ends a moment after the key, and would send a phone app that came back meanwhile behind it.
        screen.shell("am start -W -a android.intent.action.DIAL -d tel:0640000000")
        screen.shell("input keyevent KEYCODE_HOME")
        assertTrue("The home screen did not come back", PhoneTests.awaitActiveApp(screen.session, PhoneTests.homePackage()))
        assertEquals(Outcome.DONE, CommandTests.say("call 064 123 4567", CALL_MILLIS).outcome)

        CommandTests.awaitSaid("Calling 0 6 4 1 2 3 4 5 6 7.")
        assertTrue("No call is active", VoiceTests.waitUntil(15_000) { listening.state.value.mode == Mode.IN_CALL })
        // The emulator's in-call screen names its end control "End call" (plan 2D).
        assertTrue("No end-call control", VoiceTests.waitUntil(5_000) { screen.session.actions.clickLabelled("End call", EmergencyGuard.PHONE_PACKAGES) })
        assertTrue("Listening did not come back", VoiceTests.waitUntil(15_000) { listening.state.value.mode == Mode.LISTENING })
    }

    @Test
    fun cancelDuringTheCountdownLeavesTheNumberInThePhoneApp() = runBlocking<Unit> {
        CommandTests.send("call 064 123 4567")
        assertTrue("No countdown", VoiceTests.waitUntil(COUNTDOWN_STARTS) { listening.state.value.counting })

        assertEquals(Outcome.CANCELLED, CommandTests.answer("cancel").outcome)

        screen.awaitElement { it.viewId?.endsWith(":id/digits") == true }
        Thread.sleep(COUNTDOWN_AND_MORE)
        assertTrue(listening.state.value.mode == Mode.LISTENING)
    }

    @Test
    fun aTouchDuringTheCountdownCancelsIt() = runBlocking<Unit> {
        TestFinger(screen.automation).use { finger ->
            CommandTests.send("call 064 123 4567")
            assertTrue("No countdown", VoiceTests.waitUntil(COUNTDOWN_STARTS) { listening.state.value.counting })
            screen.awaitInputWindow("Kestrel touch watcher")
            val digits = screen.awaitElement { it.viewId?.endsWith(":id/digits") == true }.bounds

            finger.tap(digits.centerX, digits.centerY)

            assertEquals(Outcome.CANCELLED, CommandTests.next().outcome)
        }
        Thread.sleep(COUNTDOWN_AND_MORE)
        assertTrue(listening.state.value.mode == Mode.LISTENING)
    }

    @Test
    fun anEmergencyNumberOrAnUnreadableOneInThePhoneAppNeedsTheOwnersYesForAllButItsKeys() = runBlocking<Unit> {
        // Only "no" answers the question here: never "yes, call" with an emergency number, on any device. The announcement
        // comes first, and the command waits for the answer a moment before the question is said.
        CommandTests.ask("call 112", CALL_MILLIS)
        CommandTests.awaitSaid(PhoneCommandTexts.EMERGENCY_QUESTION)
        assertTrue(CommandTests.spoken.contains("Calling 1 1 2."))
        assertEquals(Outcome.CANCELLED, CommandTests.answer("no").outcome)
        val digits = screen.awaitElement { it.viewId?.endsWith(":id/digits") == true && it.text?.filter(Char::isDigit) == "112" }
        // A number that "search for" typed into the phone app's search could start a call, so there it takes only names.
        assertEquals(Outcome.FAILED, CommandTests.say("search for 112").outcome)
        CommandTests.awaitSaid(PhoneCommandTexts.SEARCH_NAMES_ONLY)
        // The number field has the focus, but its input type is a phone number's, so plan 3B's dictation leaves a bare
        // "call" to the phone part. A number names any element, so "expand", "collapse" and "drag" with the call button's
        // number need the yes too, and so does a scroll, whose gesture starts in the phone app's window.
        assertEquals(Outcome.DONE, CommandTests.say("show numbers").outcome)
        val number = badge { it.viewId?.endsWith(":id/dialpad_voice_call_button") == true }
        for (said in listOf("call", "tap dial", "expand $number", "collapse $number", "drag $number to $number", "scroll down")) {
            assertEquals(said, PhoneCommandTexts.EMERGENCY_QUESTION, CommandTests.ask(said))
            assertEquals(said, Outcome.CANCELLED, CommandTests.answer("no").outcome)
        }
        // The digit keys and the delete key need no yes, so that the owner can correct the number by voice.
        assertEquals(Outcome.DONE, CommandTests.say("tap ${badge { it.viewId?.endsWith(":id/five") == true }}").outcome)
        screen.awaitElement { it.key == digits.key && it.text?.filter(Char::isDigit) == "1125" }
        assertEquals(Outcome.DONE, CommandTests.say("tap ${badge { it.viewId?.endsWith(":id/deleteButton") == true }}").outcome)
        screen.awaitElement { it.key == digits.key && it.text?.filter(Char::isDigit) == "112" }
        assertEquals(Outcome.DONE, CommandTests.say("hide numbers").outcome)
        assertTrue("A call started", listening.state.value.mode == Mode.LISTENING)

        // A field that reads as no number hides what the phone app would dial, so the window needs the yes too; a guard
        // that took only the digits, or let an unreadable field through, would press Call here.
        screen.session.actions.setText(digits.key, "abc")
        screen.awaitElement { it.key == digits.key && it.text == "abc" }
        assertEquals(PhoneCommandTexts.EMERGENCY_QUESTION, CommandTests.ask("tap dial"))
        assertEquals(Outcome.CANCELLED, CommandTests.answer("no").outcome)
        Thread.sleep(COUNTDOWN_AND_MORE)
        assertTrue(listening.state.value.mode == Mode.LISTENING)
    }

    @Test
    fun anElementNamedEmergencyIsClickedOnlyAfterYesCall() = runBlocking<Unit> {
        screen.open("PhoneNumberActivity", "Phone numbers fixture")

        // The fixture's "Emergency call" button calls no one; it only shows that it was clicked.
        assertEquals(PhoneCommandTexts.EMERGENCY_QUESTION, CommandTests.ask("tap emergency call"))
        assertEquals(Outcome.CANCELLED, CommandTests.answer("no").outcome)
        Thread.sleep(1_000)
        assertTrue(screen.session.snapshots.current().snapshot.elements.none { it.label == "Emergency call pressed" })

        assertEquals(PhoneCommandTexts.EMERGENCY_QUESTION, CommandTests.ask("tap emergency call"))
        assertEquals(Outcome.DONE, CommandTests.answer("yes, call").outcome)
        screen.awaitElement { it.packageName == FIXTURES && it.label == "Emergency call pressed" }
    }

    @Test
    fun aYesAfterTheScreenChangedUnderTheQuestionEndsTheCommand() = runBlocking<Unit> {
        screen.open("PhoneNumberActivity", "Phone numbers fixture")
        assertEquals(PhoneCommandTexts.EMERGENCY_QUESTION, CommandTests.ask("tap emergency call"))

        // An accessibility click, which is no touch, so the question stays: the button's own click action, never the gesture
        // that ScreenActions.tap falls back to when an action fails, which the touch watcher would take for the owner's
        // touch. Another "Emergency call" button takes the place of the one that the question was about.
        val rearrange = screen.awaitElement { it.viewId?.endsWith(":id/rearrange_button") == true }
        val node = screen.session.snapshots.current().node(rearrange.key)
        assertTrue("Rearrange took no click", node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        screen.awaitElement { it.viewId?.endsWith(":id/emergency_button_moved") == true }

        assertEquals(Outcome.FAILED, CommandTests.answer("yes, call").outcome)
        CommandTests.awaitSaid(CommandTexts.SCREEN_CHANGED)
        Thread.sleep(1_000)
        assertTrue(screen.session.snapshots.current().snapshot.elements.none { it.label == "Emergency call pressed" })
    }

    // The number that the numbers overlay shows on the element that [which] picks, once the numbers have followed the screen
    // and stayed for a second: the phone app adds rows under a longer number, and a number read before the overlay numbered
    // the screen again would name another element, such as a digit key, by the time Kestrel hears it.
    private suspend fun badge(which: (ScreenElement) -> Boolean): Int {
        val session = screen.session
        return withTimeoutOrNull(BADGE_MILLIS) {
            var number: Int? = null
            while (number == null) {
                val snapshot = session.snapshots.current().snapshot
                val shown = withContext(Dispatchers.Main) { session.numberBadges }
                val settled = SystemClock.uptimeMillis() - session.numbersChangedAt >= SETTLED_MILLIS &&
                    Numbering.sameNumbers(shown, Numbering.forScreen(snapshot, NumbersMode.ALL))
                val key = snapshot.elements.firstOrNull(which)?.key
                number = shown.firstOrNull { it.key == key }?.number?.takeIf { settled }
                if (number == null) delay(POLL_MILLIS)
            }
            number
        } ?: throw AssertionError("The numbers did not settle on the element within $BADGE_MILLIS ms")
    }

    @Test
    fun aNameAsksWhichOneAndWhichNumberAndIsAnnounced() {
        PhoneTests.addContact("Ana", "Petrović", "0641234567" to PhoneTests.MOBILE, "0112345678" to PhoneTests.WORK)
        PhoneTests.addContact("Ana", "Jovanović", "0657654321" to PhoneTests.MOBILE)

        assertEquals("Which one? 1, Ana Jovanović. 2, Ana Petrović.", CommandTests.ask("call Ana"))
        CommandTests.send("2")
        assertTrue("No second question", VoiceTests.waitUntil(10_000) { CommandTests.spoken.lastOrNull() == "Which number? Mobile or work?" })
        CommandTests.send("work")
        assertTrue("No countdown", VoiceTests.waitUntil(COUNTDOWN_STARTS) { listening.state.value.counting })

        assertEquals("Calling Ana Petrović, work.", CommandTests.spoken.last())
        assertEquals(Outcome.CANCELLED, CommandTests.answer("stop").outcome)
    }

    @Test
    fun copyThisPhoneNumberCopiesTheOnlyNumberOrTheOneThatTheOwnerChooses() = runBlocking<Unit> {
        screen.open("PhoneNumberActivity", "Phone numbers fixture", "--ez single true")
        assertEquals(Outcome.DONE, CommandTests.say("copy this phone number").outcome)
        screen.session.actions.tap(screen.awaitElement { it.packageName == FIXTURES && it.label == "Paste" }.key)
        screen.awaitElement { it.label == "Pasted +381 11 234 5678" }

        screen.open("PhoneNumberActivity", "Phone numbers fixture")
        assertEquals(CommandTexts.WHICH_ONE, CommandTests.ask("copy the phone number"))
        // The numbers go from the top: the office's is 1 and the mobile's is 2. The date is no phone number.
        assertEquals(2, screen.session.numberBadges.size)
        assertEquals(Outcome.DONE, CommandTests.answer("2").outcome)
        screen.session.actions.tap(screen.awaitElement { it.packageName == FIXTURES && it.label == "Paste" }.key)
        screen.awaitElement { it.label == "Pasted 064 123 4567" }
    }

    private companion object {
        // The 4 s countdown and a little more, after which a call that was not cancelled would have started.
        const val COUNTDOWN_AND_MORE = 6_000L

        // Kestrel waits up to 10 s for the phone app, which a cold start on the emulator takes about 4 s to show, and then up
        // to 3 s for its call button; the announcement and the countdown or the question come after that.
        const val COUNTDOWN_STARTS = 20_000L
        const val CALL_MILLIS = 30_000L

        const val BADGE_MILLIS = 10_000L
        const val SETTLED_MILLIS = 1_000L
        const val POLL_MILLIS = 100L
    }
}
