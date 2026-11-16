package dev.kestrel.app.voice

import android.accessibilityservice.AccessibilityService
import android.os.ParcelFileDescriptor
import dev.kestrel.app.debug.ClipMicrophone
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.app.orchestrator.State
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.voice.models.Engine
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The ways into listening without a word (design spec, section 8.6): the accessibility shortcut and the accessibility
 * button, which both reach KestrelAccessibilityService's button callback. Kestrel is in the background, as when the owner
 * uses them. The test process is instrumented, which Android may count as a reason to let Kestrel start a foreground
 * service from the background, so these tests check the wiring; scripts\listen-check.ps1 checks the start paths
 * themselves. Each test puts back the settings that it changes, and leaves Kestrel off and its accessibility service off,
 * as the voice tests after it expect.
 */
class ActivationTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val listening = VoiceTests.graph.listening
    private val clip = ClipMicrophone()

    @Before
    fun setUp() {
        VoiceTests.assumeModels(Engine.VAD, Engine.KWS, Engine.ASR, Engine.TTS)
        VoiceTests.grantListeningPermissions()
        VoiceTests.useMicrophone(clip)
        VoiceTests.shell("input keyevent KEYCODE_HOME")
    }

    @After
    fun tearDown() {
        try {
            VoiceTests.stopListening()
        } finally {
            turnOffTheAccessibilityService()
        }
    }

    // The voice tests after this one run without Kestrel's accessibility service, as they did before this test existed. Its
    // binding alone lets Kestrel record and play in the background while the screen is on (prototype P1, Q1), which would
    // hide a ListeningService that does not give Kestrel these rights (ConversationTest, B5).
    private fun turnOffTheAccessibilityService() {
        VoiceTests.shell("settings delete secure enabled_accessibility_services")
        check(VoiceTests.waitUntil(10_000) { VoiceTests.graph.screen.value == null }) { "Kestrel's accessibility service is still connected" }
    }

    @Test
    fun theShortcutAndTheButtonToggleListening() {
        val saved = TARGETS.associateWith { secure(it) }
        try {
            putSecure(SHORTCUT, SERVICE)
            // Android reads the setting a moment after it changes.
            assertTrue(
                "Android does not name Kestrel as the shortcut's target",
                VoiceTests.waitUntil(5_000) { SHORTCUT_KEY.containsMatchIn(VoiceTests.shell("dumpsys accessibility")) },
            )

            assertTrue("The shortcut's action failed", press(AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_SHORTCUT))
            awaitListening()
            assertTrue("The shortcut's action failed", press(AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_SHORTCUT))
            awaitState { it.mode == Mode.PAUSED }

            // With gestural navigation, as on kestrel-api37, Android offers the button's action only for a gesture target;
            // with the navigation bar's buttons, for a button target in the navigation bar (prototype P1, research R14).
            putSecure(BUTTON, SERVICE)
            putSecure(GESTURE, SERVICE)
            putSecure(BUTTON_MODE, "0")
            // SystemUI offers the action a moment after it has seen the targets. Until then the call returns false and does
            // nothing, so only the call that returns true presses the button.
            assertTrue(
                "Android did not offer the accessibility button's action",
                VoiceTests.waitUntil(5_000) { press(AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_BUTTON) },
            )
            awaitState { it.mode == Mode.LISTENING }
        } finally {
            // Each setting is written back even when another write fails.
            val failures = saved.map { (name, value) -> runCatching { putSecure(name, value) } }
            failures.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
        }
    }

    /** Asks Android for a system action, as the accessibility shortcut or button does. Returns whether Android ran it. */
    private fun press(action: Int): Boolean = screen.automation.performGlobalAction(action)

    /** Waits until Kestrel listens, with its voice session open and the test's microphone recording. */
    private fun awaitListening() {
        // Loading the models takes several seconds.
        assertTrue(
            "Kestrel does not listen; the state is ${listening.state.value}",
            VoiceTests.waitUntil(60_000) { listening.state.value.mode == Mode.LISTENING && listening.sessionOpen.value && clip.recording },
        )
    }

    private fun awaitState(condition: (State) -> Boolean) {
        assertTrue("The state is ${listening.state.value}", VoiceTests.waitUntil(10_000) { condition(listening.state.value) })
    }

    /** A secure setting as the shell prints it: "null" when it is not set. */
    private fun secure(name: String): String = VoiceTests.shell("settings get secure $name").trimEnd('\r', '\n')

    /** Writes a secure setting through the device's shell, which reads quotes, so an empty value too; "null" deletes it. */
    private fun putSecure(name: String, value: String) {
        val command = if (value == "null") "settings delete secure $name" else "settings put secure $name '${value.replace("'", "'\\''")}'"
        val (output, input) = screen.automation.executeShellCommandRw("sh")
        ParcelFileDescriptor.AutoCloseOutputStream(input).use { it.write("$command\n".toByteArray()) }
        // The shell ends when its input ends, and only then its output, so the setting is written when this returns.
        ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
    }

    private companion object {
        const val SERVICE = "dev.kestrel/dev.kestrel.app.KestrelAccessibilityService"
        const val SHORTCUT = "accessibility_shortcut_target_service"
        const val BUTTON = "accessibility_button_targets"
        const val GESTURE = "accessibility_gesture_targets"
        const val BUTTON_MODE = "accessibility_button_mode"
        val TARGETS = listOf(SHORTCUT, BUTTON, GESTURE, BUTTON_MODE)

        // How `dumpsys accessibility` names the shortcut's targets, as in "shortcut key:{dev.kestrel/...}".
        val SHORTCUT_KEY = Regex("""shortcut key:\{[^}]*dev\.kestrel/""")
    }
}
