package dev.kestrel.app.voice

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.MainActivity
import dev.kestrel.app.R
import dev.kestrel.app.debug.ClipMicrophone
import dev.kestrel.app.listen.AndroidListeningConditions
import dev.kestrel.app.listen.ListeningService
import dev.kestrel.app.listen.ListeningTexts
import dev.kestrel.app.listen.ProblemNotifications
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.app.orchestrator.State
import dev.kestrel.voice.models.Engine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ListeningService (design spec, sections 7.2, 8.5, 8.6 and 14.6): the foreground, the notification's actions, the
 * screen and the lock, a stop that Kestrel did not ask for and the problems notification that it posts, and the start
 * from that notification's "Start listening". The lock-screen test runs only on emulators: it skips itself on a phone,
 * and scripts\voice-test.ps1 leaves it out there.
 */
class ListeningServiceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val listening = VoiceTests.graph.listening
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val clip = ClipMicrophone()

    @Before
    fun setUp() {
        VoiceTests.assumeModels(Engine.VAD, Engine.KWS, Engine.ASR, Engine.TTS)
        VoiceTests.grantListeningPermissions()
    }

    @After
    fun tearDown() {
        // The problems notification of a stop by Android is not left for the next tests.
        notifications.cancel(ProblemNotifications.STOPPED_ID)
        // A test that failed while the screen was off must not leave it off for the next tests.
        VoiceTests.shell("input keyevent KEYCODE_WAKEUP")
        VoiceTests.shell("wm dismiss-keyguard")
        VoiceTests.stopListening()
    }

    @Test
    fun theServiceRunsInTheForegroundAndItsNotificationPausesResumesAndStops() {
        ActivityScenario.launch(MainActivity::class.java).use {
            VoiceTests.startListening(clip)

            // Android 13 prints the foreground but no types (analysis of the A71 session of sub-project 2, item 1), so the
            // types come from the running service, which has them while it is in the foreground.
            assertTrue("Android's record of the service shows no foreground", dumpedForeground())
            val types = foregroundTypes()
            assertTrue(
                "Foreground service types 0x${types.toString(16)}",
                (types and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) != 0 &&
                    (types and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) != 0,
            )
            // Where Android prints the types, as Android 14 ("types=%08X") and later ("types=0x%08X") do, they agree.
            dumpedTypes()?.let { assertEquals(types, it) }
            assertEquals(texts(R.string.listening_pause, R.string.listening_stop), actions(R.string.listening_title_listening))

            press(R.string.listening_pause)
            awaitState { it.mode == Mode.PAUSED }
            assertEquals(texts(R.string.listening_resume, R.string.listening_stop), actions(R.string.listening_title_paused))

            press(R.string.listening_resume)
            awaitState { it.mode == Mode.LISTENING }
            assertEquals(texts(R.string.listening_pause, R.string.listening_stop), actions(R.string.listening_title_listening))

            press(R.string.listening_stop)
            awaitState { it.mode == Mode.OFF }
            assertTrue("The voice session is still open", VoiceTests.waitUntil(30_000) { !listening.sessionOpen.value })
            assertTrue("ListeningService still runs", VoiceTests.waitUntil(10_000) { !VoiceTests.listeningServiceRuns() })
        }
    }

    @Test
    fun theNotificationsActionsWorkWithKestrelInTheBackground() {
        ActivityScenario.launch(MainActivity::class.java).use { VoiceTests.startListening(clip) }
        // As when the owner uses the notification over another app, with the screen on.
        VoiceTests.shell("input keyevent KEYCODE_HOME")
        assertEquals(texts(R.string.listening_pause, R.string.listening_stop), actions(R.string.listening_title_listening))
        // Section 8.6: broadcasts, which need no new start of the service. Before this task they started the service.
        val shown = notifications.activeNotifications.single { it.id == ListeningService.NOTIFICATION_ID }.notification
        assertTrue("An action still starts the service", shown.actions.all { it.actionIntent.isBroadcast })

        press(R.string.listening_pause)
        awaitState { it.mode == Mode.PAUSED }
        assertEquals(texts(R.string.listening_resume, R.string.listening_stop), actions(R.string.listening_title_paused))
        press(R.string.listening_resume)
        awaitState { it.mode == Mode.LISTENING }
        assertEquals(texts(R.string.listening_pause, R.string.listening_stop), actions(R.string.listening_title_listening))
        assertTrue("The microphone does not record", VoiceTests.waitUntil(5_000) { clip.recording })

        press(R.string.listening_stop)
        awaitState { it.mode == Mode.OFF }
        assertTrue("The voice session is still open", VoiceTests.waitUntil(30_000) { !listening.sessionOpen.value })
        assertTrue("ListeningService still runs", VoiceTests.waitUntil(10_000) { !VoiceTests.listeningServiceRuns() })
    }

    @Test
    fun theMicrophoneStopsWhileTheScreenIsOffAndStartsAgainAfterTheUnlock() {
        VoiceTests.assumeEmulator()
        ActivityScenario.launch(MainActivity::class.java).use { VoiceTests.startListening(clip) }
        // kestrel-api37 has no lock screen. A phone has one, and only ACTION_USER_PRESENT, which SystemUI sends, tells
        // Kestrel that the owner unlocked it. So the test turns on the swipe lock screen, and turns it off again.
        val noLockScreen = VoiceTests.shell("cmd lock_settings get-disabled").trim() == "true"
        if (noLockScreen) VoiceTests.shell("cmd lock_settings set-disabled false")
        try {
            VoiceTests.shell("input keyevent KEYCODE_SLEEP")
            awaitState { !it.screenOn && !it.microphone }
            assertTrue("The microphone still records with the screen off", VoiceTests.waitUntil(5_000) { !clip.recording })

            VoiceTests.shell("input keyevent KEYCODE_WAKEUP")
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            assertTrue("No lock screen after the wake-up", VoiceTests.waitUntil(5_000) { keyguard.isKeyguardLocked })
            Thread.sleep(1_000)
            assertFalse("Kestrel records on the lock screen", listening.state.value.screenOn || clip.recording)

            VoiceTests.shell("wm dismiss-keyguard")
            awaitState { it.screenOn && it.microphone }
            assertTrue("The microphone does not record after the unlock", VoiceTests.waitUntil(5_000) { clip.recording })
            // Section 8.5: after the unlock Kestrel resumes the mode that it had.
            assertEquals(Mode.LISTENING, listening.state.value.mode)
        } finally {
            VoiceTests.shell("wm dismiss-keyguard")
            if (noLockScreen) VoiceTests.shell("cmd lock_settings set-disabled true")
        }
    }

    @Test
    fun aNewServiceReportsTheScreenAndAStopByAndroidTurnsKestrelOff() {
        // A screen state that an earlier run left: only the new service's first report can correct it, and
        // startListening waits until the microphone records.
        listening.send(Event.Screen(on = false))
        awaitState { !it.screenOn }
        ActivityScenario.launch(MainActivity::class.java).use { VoiceTests.startListening(clip) }

        // As when Android ends the service on its own: Kestrel did not ask for it.
        context.stopService(Intent(context, ListeningService::class.java))
        awaitState { it.mode == Mode.OFF }
        assertTrue("The voice session is still open", VoiceTests.waitUntil(30_000) { !listening.sessionOpen.value })

        // Section 14.6: the reason is a notification too, with "Start listening", because the bar may not exist.
        val problem = problemNotification()
        assertEquals(ListeningTexts.STOPPED_BY_ANDROID, problem.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertEquals(texts(R.string.problem_start), problem.actions.map { it.title.toString() })
    }

    @Test
    fun theProblemNotificationsStartActionTurnsKestrelOn() {
        VoiceTests.useMicrophone(clip)
        // In front, as after a tap on the notification, which lets Kestrel start its microphone service (spike S1).
        ActivityScenario.launch(MainActivity::class.java).use {
            ListeningService.startIntent(context).send()
            assertTrue("The voice session did not open", VoiceTests.waitUntil(60_000) { listening.sessionOpen.value })
        }

        assertEquals(Mode.LISTENING, listening.state.value.mode)
        assertTrue("The microphone does not record", VoiceTests.waitUntil(5_000) { clip.recording })
    }

    @Test
    fun aServiceThatLostItsForegroundCountsAsDemoted() {
        val conditions = AndroidListeningConditions(context)
        ActivityScenario.launch(MainActivity::class.java).use { VoiceTests.startListening(clip) }
        val service = runningService()
        assertFalse("A service in the foreground counts as demoted", conditions.demoted(service))
        try {
            // As Android does to a service whose app's battery use is Restricted (prototype P2): without flags, so that
            // the record keeps its notification's ID; Android 13 keeps the service's type then (analysis of the A71
            // session of sub-project 2, item 1).
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                service.stopForeground(Service.STOP_FOREGROUND_LEGACY)
            }
            assertTrue(
                "A service without the foreground does not count as demoted",
                VoiceTests.waitUntil(5_000) { conditions.demoted(service) },
            )
            assertEquals(false, conditions.listedForeground())
        } finally {
            VoiceTests.stopListening()
            // The service's notification of the turn-off no longer belongs to a foreground, so nothing else removes it.
            notifications.cancel(ListeningService.NOTIFICATION_ID)
        }
    }

    private fun texts(vararg ids: Int): List<String> = ids.map { context.getString(it) }

    /** The titles of the actions of the service's notification, once its title is [title]. */
    private fun actions(title: Int): List<String> = notification(title).actions.map { it.title.toString() }

    private fun notification(title: Int): Notification {
        val expected = context.getString(title)
        var found: Notification? = null
        val shown = VoiceTests.waitUntil(5_000) {
            found = notifications.activeNotifications.firstOrNull { it.id == ListeningService.NOTIFICATION_ID }
                ?.notification
                ?.takeIf { it.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == expected }
            found != null
        }
        assertTrue("No notification titled '$expected'", shown)
        return found!!
    }

    /** The problems notification, once it shows (section 14.6). */
    private fun problemNotification(): Notification {
        var found: Notification? = null
        val shown = VoiceTests.waitUntil(5_000) {
            found = notifications.activeNotifications.firstOrNull { it.id == ProblemNotifications.STOPPED_ID }?.notification
            found != null
        }
        assertTrue("No problems notification", shown)
        return found!!
    }

    /** Sends the intent of the notification's action [title], as a tap on it would. */
    private fun press(title: Int) {
        val label = context.getString(title)
        val notification = notifications.activeNotifications.single { it.id == ListeningService.NOTIFICATION_ID }.notification
        notification.actions.single { it.title.toString() == label }.actionIntent.send()
    }

    private fun awaitState(condition: (State) -> Boolean) {
        assertTrue("The state is ${listening.state.value}", VoiceTests.waitUntil(10_000) { condition(listening.state.value) })
    }

    /** The running ListeningService. */
    private fun runningService(): ListeningService {
        var found: ListeningService? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { found = ListeningService.running() }
        return checkNotNull(found) { "ListeningService does not run" }
    }

    /** ListeningService's foreground service types, from the running service itself. */
    private fun foregroundTypes(): Int {
        val service = runningService()
        var types = 0
        InstrumentationRegistry.getInstrumentation().runOnMainSync { types = service.foregroundServiceType }
        return types
    }

    /** Whether Android's record of ListeningService says that it runs in the foreground, which every version prints. */
    private fun dumpedForeground(): Boolean = FOREGROUND.containsMatchIn(services())

    /** The types that Android's record prints after the foreground, or null where it prints none, as Android 13 does. */
    private fun dumpedTypes(): Int? = TYPES.find(services())?.groupValues?.get(1)?.toInt(16)

    private fun services(): String = VoiceTests.shell("dumpsys activity services dev.kestrel")

    private companion object {
        val RECORD = """isForeground=true foregroundId=${ListeningService.NOTIFICATION_ID}\b"""
        val FOREGROUND = Regex(RECORD)
        val TYPES = Regex(RECORD + """.*?\btypes=(?:0x)?([0-9a-fA-F]+)""")
    }
}
