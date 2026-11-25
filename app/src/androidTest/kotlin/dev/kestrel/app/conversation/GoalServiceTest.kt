package dev.kestrel.app.conversation

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.agent.EndReason
import dev.kestrel.agent.testing.ScriptedModel
import dev.kestrel.agent.testing.ScriptedModel.Companion.call
import dev.kestrel.app.agent.AgentTests
import dev.kestrel.app.agent.HeldModel
import dev.kestrel.app.debug.NoticeButtons
import dev.kestrel.app.goal.ForegroundType
import dev.kestrel.app.goal.GoalActionReceiver
import dev.kestrel.app.goal.GoalNotification
import dev.kestrel.app.goal.GoalService
import dev.kestrel.app.goal.GoalTexts
import dev.kestrel.app.listen.ListeningService
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.conversation.ConversationTexts
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.model.GoalPhase
import dev.kestrel.safety.EmergencyGuard
import dev.kestrel.screen.act.GlobalAction
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The goal service's wiring on kestrel-api37 (design 10.6 and 15.2 of sub-project 7; plan 7E): it runs for every open goal
 * with listening on or off, its notification follows the goal, its buttons reach the conversation, a held goal offers
 * Cancel before Continue and Continue asks for the owner's unlock, a held goal's Continue starts it again, the lock and call
 * pauses with listening off ask "Continue the goal?" in it and let go of the screen's turn at once, the shade shows the held goal's buttons without an expansion while listening's notification is there too, a pull of the shade
 * while the goal acts holds the goal, and it adds mediaPlayback while Kestrel speaks. The model's replies wait until the
 * test lets each one go (HeldModel), so that no step depends on the emulator's speed. The tests run in Kestrel's own
 * process, where Android lets the instrumentation start foreground services from the background, so they check the wiring,
 * and the controller checks Android's own rules for the starts and for background audio outside the tests (plan 7E, Task
 * 8). kestrel-api37 has no lock screen, so waking the screen unlocks it. Each test starts without a conversation
 * (`AgentTests.run` closes it through `ConversationTests`), and `AgentTests.stop` ends whatever "stop" stops.
 */
@RunWith(AndroidJUnit4::class)
class GoalServiceTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph get() = AgentTests.graph
    private val conversations get() = graph.conversations

    // A say passes the agent's gate at the next turn, where a paused goal waits; fail ends the goal.
    private val onIt = call("say", null, null, null, "text" to "On it.")
    private val fail = call("fail", null, null, null, "reason" to "I stop here.")

    @Before
    fun start() {
        VoiceTests.assumeEmulator()
        AgentTests.start()
    }

    @After
    fun stop() {
        wake()
        screen.shell("cmd statusbar collapse")
        AgentTests.stop()
        check(VoiceTests.waitUntil(15_000) { GoalService.runningTypes() == null }) { "The goal service still runs" }
    }

    @Test
    fun aGoalRunsTheServiceWithListeningOnAndOffUntilItsFinalReply() {
        val model = HeldModel(ScriptedModel(listOf(onIt, fail)))
        val command = AgentTests.run("read my newest notification", model)
        awaitCalls(model, 1)

        val working = awaitShown("the working goal") { it.title == GoalTexts.WORKING }
        assertEquals(listOf(GoalTexts.STOP, GoalTexts.OPEN), working.actions)
        awaitTypes(setOf(ForegroundType.SPECIAL_USE))

        // Decision 10 of plan 7B: turning Kestrel off leaves the goal working, and the service with it (D19).
        AgentTests.turnOff()
        assertEquals(GoalPhase.ACTING, conversations.view.value.goal)
        assertEquals(setOf(ForegroundType.SPECIAL_USE), GoalService.runningTypes())

        model.release()
        awaitCalls(model, 2)
        model.release()
        assertEquals(EndReason.FAILED_BY_MODEL, AgentTests.ended(command).report.end)
        check(VoiceTests.waitUntil(15_000) { GoalService.runningTypes() == null && shown() == null }) { "The service outlived the goal" }
    }

    @Test
    fun theNotificationsStopEndsTheWorkingGoal() {
        val model = HeldModel(ScriptedModel(listOf(onIt, fail)))
        val command = AgentTests.run("read my newest notification", model)
        awaitCalls(model, 1)
        awaitShown("Stop") { GoalTexts.STOP in it.actions }

        press(GoalTexts.STOP)

        assertEquals(EndReason.CANCELLED, AgentTests.ended(command).report.end)
        assertEquals(1, model.calls)
    }

    @Test
    fun aHeldGoalOffersCancelBeforeContinueWhichAsksForTheUnlockAndResumesTheGoalWithANewStart() {
        val model = HeldModel(ScriptedModel(listOf(onIt, fail)))
        AgentTests.run("read my newest notification", model)
        awaitCalls(model, 1)
        awaitTypes(setOf(ForegroundType.SPECIAL_USE))
        val starts = graph.goalForeground.status().starts

        // D13: a touch holds the goal; the conversation takes it as the touch watcher gives it.
        conversations.touched()
        val held = awaitShown("Continue the goal?") { it.title == ConversationTexts.CONTINUE_GOAL }
        assertEquals(ConversationTexts.GO_ON, held.text)
        // The owner's decisions of 2026-11-25: Cancel first, and only Continue, which resumes the goal, asks for the unlock.
        assertEquals(listOf(GoalTexts.CANCEL, GoalTexts.CONTINUE), held.actions)
        val buttons = checkNotNull(notification()).actions.associateBy { it.title.toString() }
        assertTrue(checkNotNull(buttons[GoalTexts.CONTINUE]).isAuthenticationRequired)
        assertFalse(checkNotNull(buttons[GoalTexts.CANCEL]).isAuthenticationRequired)
        assertEquals(GoalPhase.INTERRUPTED, conversations.view.value.goal)
        assertEquals(starts, graph.goalForeground.status().starts)

        press(GoalTexts.CONTINUE)

        // Design 10.6: the resume starts the service again, from the notification's broadcast.
        awaitCalls(model, 2)
        val resumed = conversations.view.value.command
        awaitStarts(starts + 1)
        assertEquals(0, graph.goalForeground.status().refusals)
        model.release()
        awaitCalls(model, 3)
        model.release()
        assertEquals(EndReason.FAILED_BY_MODEL, AgentTests.ended(resumed).report.end)
    }

    @Test
    fun aPullOfTheShadeWhileTheGoalActsIsATouchThatHoldsTheGoal() {
        val model = HeldModel(ScriptedModel(listOf(onIt, fail)))
        AgentTests.run("read my newest notification", model)
        awaitCalls(model, 1)
        val width = context.resources.displayMetrics.widthPixels
        val height = context.resources.displayMetrics.heightPixels

        // Design 10.6: the owner's way to the notification's buttons is a pull of the shade, which is a touch (D13), so
        // the goal is held before he can tap a button, and the first button then is Cancel.
        screen.shell("input swipe ${width / 2} 5 ${width / 2} ${height / 2}")

        check(VoiceTests.waitUntil(15_000) { conversations.view.value.goal == GoalPhase.INTERRUPTED }) { "The pull of the shade did not hold the goal" }
        val held = awaitShown("Continue the goal?") { it.title == ConversationTexts.CONTINUE_GOAL }
        assertEquals(listOf(GoalTexts.CANCEL, GoalTexts.CONTINUE), held.actions)
    }

    @Test
    fun withListeningOnTheShadeShowsTheHeldGoalsCancelAndContinueWithoutExpandingAnything() {
        val model = HeldModel(ScriptedModel(listOf(onIt, fail)))
        AgentTests.run("read my newest notification", model)
        awaitCalls(model, 1)
        // Listening's notification is Kestrel's second one, which Android would put in one collapsed group with the goal's.
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == ListeningService.NOTIFICATION_ID })
        conversations.touched()
        awaitShown("Continue the goal?") { it.title == ConversationTexts.CONTINUE_GOAL }

        runBlocking { screen.session.actions.global(GlobalAction.NOTIFICATIONS) }
        // Design 10.6: the owner sees the held goal's buttons in the shade as it opens, with no row or group to expand first.
        check(VoiceTests.waitUntil(5_000) {
            val shade = runBlocking { screen.session.snapshots.current() }.snapshot
            listOf(GoalTexts.CANCEL, GoalTexts.CONTINUE).all { NoticeButtons.button(shade, ConversationTexts.CONTINUE_GOAL, it) != null }
        }) { "The shade does not show the held goal's Cancel and Continue without an expansion" }
    }

    @Test
    fun withListeningOffTheNotificationsContinueAnswersContinueTheGoalAfterALock() {
        val model = HeldModel(ScriptedModel(listOf(onIt, fail)))
        AgentTests.run("read my newest notification", model)
        awaitCalls(model, 1)
        AgentTests.turnOff()
        val starts = graph.goalForeground.status().starts

        screen.shell("input keyevent KEYCODE_SLEEP")
        check(VoiceTests.waitUntil(15_000) { conversations.view.value.goal == GoalPhase.PAUSED }) { "The lock did not pause the goal" }
        // The round is inside its first model call, which held the screen's turn; the pause is a wait for the owner, so the
        // round has let go of it at once (plan 7E; plan 7D's re-review, R5-1).
        assertFalse(graph.screenTurns.roundHolds())
        awaitShown("Continue the goal?") { it.title == ConversationTexts.CONTINUE_GOAL }
        // The model's first reply goes; the paused goal waits at its next step and asks the model nothing more.
        model.release()
        wake()
        check(VoiceTests.waitUntil(30_000) { conversations.view.value.asking == QuestionKind.CONTINUE_GOAL }) { "No \"Continue the goal?\" after the unlock" }
        assertEquals(1, model.calls)

        // With listening off, the notification answers it (and, from plan 7F, the conversation screen).
        press(GoalTexts.CONTINUE)

        awaitCalls(model, 2)
        awaitStarts(starts + 1)
        model.release()
        check(VoiceTests.waitUntil(30_000) { conversations.view.value.goal == null }) { "The goal did not end" }
        assertEquals(EndReason.FAILED_BY_MODEL, conversations.outcome(conversations.view.value.command)?.report?.end)
    }

    @Test
    fun aCallPausesTheGoalWithListeningOffUntilTheNotificationsContinue() {
        assumeTrue(
            "Needs Kestrel's phone-state permission, which plan 7E's Task 8 gives for this run and takes back",
            context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED,
        )
        val model = HeldModel(ScriptedModel(listOf(onIt, fail)))
        AgentTests.run("read my newest notification", model)
        awaitCalls(model, 1)
        AgentTests.turnOff()

        // No action of the agent's came before the call, so the call backstop leaves it alone: the owner placed it.
        screen.shell("am start -W -a android.intent.action.CALL -d tel:0640000000")
        check(VoiceTests.waitUntil(15_000) { conversations.view.value.goal == GoalPhase.PAUSED }) { "The call did not pause the goal" }
        awaitShown("Continue the goal?") { it.title == ConversationTexts.CONTINUE_GOAL }
        model.release()
        endCall()
        check(VoiceTests.waitUntil(30_000) { conversations.view.value.asking == QuestionKind.CONTINUE_GOAL }) { "No \"Continue the goal?\" after the call" }

        press(GoalTexts.CONTINUE)

        awaitCalls(model, 2)
        model.release()
        check(VoiceTests.waitUntil(30_000) { conversations.view.value.goal == null }) { "The goal did not end" }
        screen.shell("input keyevent KEYCODE_HOME")
    }

    /**
     * What this checks: while Kestrel speaks, the running service gets the type mediaPlayback in Android itself (its
     * `foregroundServiceType`), which means that Android accepted the second `startForeground()` with the added type and
     * that the speech waited for it, and the service drops the type after the speech. It does not check that Android plays
     * the audio: in the instrumented process the audio focus is given with or without the service, so Task 8 checks that
     * outside it, with the hook `goal-control do=speak|focus`.
     */
    @Test
    fun whileKestrelSpeaksTheRunningServiceHasMediaPlaybackAndDropsItAfter() = runBlocking<Unit> {
        assumeTrue("Android checks the types from Android 14 on", Build.VERSION.SDK_INT >= 34)
        val model = HeldModel(ScriptedModel(listOf(fail)))
        AgentTests.run("read my newest notification", model)
        awaitCalls(model, 1)
        AgentTests.turnOff()
        awaitTypes(setOf(ForegroundType.SPECIAL_USE))

        val during = graph.goalForeground.whileSpeaking { GoalService.runningTypes() }

        assertEquals(setOf(ForegroundType.SPECIAL_USE, ForegroundType.MEDIA_PLAYBACK), during)
        awaitTypes(setOf(ForegroundType.SPECIAL_USE))
        model.release()
    }

    // The second review of plan 7E (R2-I1): SystemUI may hold an older goal's button, as a Continue that waits for the
    // owner's unlock, while a newer goal is held. Each goal's buttons have PendingIntents of their own, so the older
    // Continue names the older goal, and the newer goal stays held.
    @Test
    fun anOlderGoalsContinueNeverResumesTheNextGoal() {
        val first = HeldModel(ScriptedModel(listOf(onIt, fail)))
        AgentTests.run("read my newest notification", first)
        awaitCalls(first, 1)
        conversations.touched()
        awaitShown("the first goal held") { it.title == ConversationTexts.CONTINUE_GOAL }
        val olderContinue = checkNotNull(notification()).actions.single { it.title.toString() == GoalTexts.CONTINUE }.actionIntent
        ConversationTests.endWork()

        val second = HeldModel(ScriptedModel(listOf(onIt, fail)))
        AgentTests.run("read my newest notification", second)
        awaitCalls(second, 1)
        conversations.touched()
        check(VoiceTests.waitUntil(15_000) { conversations.view.value.goal == GoalPhase.INTERRUPTED }) { "The touch did not hold the second goal" }
        awaitShown("the second goal held") { it.title == ConversationTexts.CONTINUE_GOAL }

        olderContinue.send()
        Thread.sleep(SETTLE_MILLIS)

        assertEquals(GoalPhase.INTERRUPTED, conversations.view.value.goal)
        assertEquals(1, second.calls)
    }

    @Test
    fun theManifestDeclaresTheServiceAndItsReceiverForKestrelAlone() {
        val packages = context.packageManager
        val service = ComponentName(context, GoalService::class.java)
        val info = packages.getServiceInfo(service, PackageManager.ComponentInfoFlags.of(0))
        assertFalse(info.exported)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, info.foregroundServiceType)
        assertTrue(packages.getProperty("android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE", service).string.orEmpty().startsWith("An on-device agent"))
        val receiver = packages.getReceiverInfo(ComponentName(context, GoalActionReceiver::class.java), PackageManager.ComponentInfoFlags.of(0))
        assertFalse(receiver.exported)
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission("android.permission.FOREGROUND_SERVICE_SPECIAL_USE"))
    }

    // The goal notification as the owner sees it: its title, its text and its buttons' labels.
    private class Shown(val title: String?, val text: String?, val actions: List<String>)

    private fun notification(): Notification? =
        context.getSystemService(NotificationManager::class.java).activeNotifications.firstOrNull { it.id == GoalNotification.SERVICE_ID }?.notification

    private fun shown(): Shown? = notification()?.let { notification ->
        Shown(
            notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            notification.actions.orEmpty().map { it.title.toString() },
        )
    }

    private fun awaitShown(what: String, condition: (Shown) -> Boolean): Shown {
        var last: Shown? = null
        check(VoiceTests.waitUntil(15_000) { shown()?.also { last = it }?.let(condition) == true }) { "The goal notification did not show $what" }
        return checkNotNull(last)
    }

    // Sends the button's PendingIntent, as SystemUI does when the owner taps it on an unlocked phone.
    private fun press(label: String) {
        checkNotNull(notification()).actions.single { it.title.toString() == label }.actionIntent.send()
    }

    private fun awaitTypes(types: Set<ForegroundType>) {
        check(VoiceTests.waitUntil(15_000) { GoalService.runningTypes() == types }) { "The goal service does not run with $types" }
    }

    // The start runs on the coordinator's collector, apart from the round that asks the model, so the test waits for it.
    private fun awaitStarts(starts: Int) {
        check(VoiceTests.waitUntil(15_000) { graph.goalForeground.status().starts == starts }) { "The goal service was not started again" }
    }

    private fun awaitCalls(model: HeldModel, calls: Int) {
        check(VoiceTests.waitUntil(30_000) { model.calls == calls }) { "The model did not get request $calls" }
    }

    private fun wake() {
        screen.shell("input keyevent KEYCODE_WAKEUP")
        screen.shell("wm dismiss-keyguard")
    }

    // The emulator's in-call screen names its end control "End call" (plan 2D); the key ends the call when it does not show.
    private fun endCall() {
        if (!VoiceTests.waitUntil(5_000) { screen.session.actions.clickLabelled("End call", EmergencyGuard.PHONE_PACKAGES) }) {
            screen.shell("input keyevent KEYCODE_ENDCALL")
        }
        check(VoiceTests.waitUntil(10_000) { !callActive() }) { "A call is still active" }
    }

    // Android's call state for each SIM: a line mCallState=0 is CALL_STATE_IDLE.
    private fun callActive() = screen.shell("dumpsys telephony.registry").lineSequence().map { it.trim() }
        .any { it.startsWith("mCallState=") && it != "mCallState=0" }

    private companion object {
        // How long a test watches a held goal stay held after a button that must not count.
        const val SETTLE_MILLIS = 3_000L
    }
}
