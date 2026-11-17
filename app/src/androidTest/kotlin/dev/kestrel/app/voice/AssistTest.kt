package dev.kestrel.app.voice

import dev.kestrel.app.debug.ClipMicrophone
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.app.orchestrator.Work
import dev.kestrel.voice.models.Engine
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The assistant gesture and the earbuds' assistant button (design spec, section 8.6; prototype P3), with Kestrel in the
 * background and its accessibility service off. Both start listening and wait for a task. With the assistant role,
 * "Kestrel, open settings" also brings Kestrel's settings to the front. The two tests of the role run only on an
 * emulator, because they make Kestrel the holder of the role: they put the holder back themselves, and
 * scripts\voice-test.ps1 puts it back at the end in case the test's process died before it could.
 */
class AssistTest {
    private val listening = VoiceTests.graph.listening

    // Hears nothing, so that the wait for a task lasts.
    private val clip = ClipMicrophone()

    @Before
    fun setUp() {
        VoiceTests.assumeModels(Engine.VAD, Engine.KWS, Engine.ASR, Engine.TTS)
        VoiceTests.grantListeningPermissions()
        check(listening.state.value.mode == Mode.OFF) { "Listening is already on" }
        // As in ActivationTest: the service would let Kestrel record in the background by itself. It also removes
        // Kestrel's notice that the service is off; the script that runs the tests puts the accessibility settings back.
        VoiceTests.turnOffAccessibilityService()
        VoiceTests.useMicrophone(clip)
        VoiceTests.shell("input keyevent KEYCODE_HOME")
    }

    @After
    fun tearDown() {
        VoiceTests.stopListening()
    }

    @Test
    fun theAssistKeyStartsListeningAndWaitsForATask() {
        // It changes the holder of the assistant role, which only voice-test.ps1 puts back if the test's process dies.
        VoiceTests.assumeEmulator()
        asTheAssistant {
            VoiceTests.shell("input keyevent KEYCODE_ASSIST")

            awaitTheWaitForATask()
            // The session hides its window at once, so that the window does not cover the app in front (prototype P3).
            assertFalse(
                "The assistant session's window has the focus",
                FOCUSED_SESSION.containsMatchIn(VoiceTests.shell("dumpsys window")),
            )
        }
    }

    @Test
    fun withTheAssistantRoleOpenSettingsBringsTheSettingsToTheFront() {
        // It changes the holder of the assistant role, as the test of the assist key does.
        VoiceTests.assumeEmulator()
        asTheAssistant {
            // The role alone lets Kestrel start its microphone service from the background (prototype P3).
            VoiceTests.startListening(clip)
            try {
                listening.send(Event.Heard("Kestrel, open settings", named = true))

                // Without the role and the accessibility service, Android would block the start without an exception.
                assertTrue(
                    "Kestrel's settings did not come to the front",
                    VoiceTests.waitUntil(10_000) {
                        SETTINGS_IN_FRONT.containsMatchIn(VoiceTests.shell("dumpsys activity activities"))
                    },
                )
            } finally {
                VoiceTests.shell("input keyevent KEYCODE_HOME")
            }
        }
    }

    /**
     * Makes Kestrel the holder of the assistant role, runs [block], and gives the role back to the app that held it,
     * which also puts back the settings that name the assistant's service.
     */
    private fun asTheAssistant(block: () -> Unit) {
        val holder = roleHolders()
        val settings = ASSISTANT_SETTINGS.associateWith { secure(it) }
        var failure: Throwable? = null
        try {
            VoiceTests.shell("cmd role add-role-holder $ROLE dev.kestrel")
            // Android points both settings at Kestrel's service a moment after the role changes, and the key goes to the
            // assistant that they name.
            assertTrue(
                "Kestrel did not become the assistant",
                VoiceTests.waitUntil(10_000) {
                    roleHolders() == "dev.kestrel" && ASSISTANT_SETTINGS.all { secure(it).startsWith("dev.kestrel/") }
                },
            )
            block()
        } catch (e: Throwable) {
            failure = e
            throw e
        } finally {
            try {
                // The role is exclusive, so adding the earlier holder removes Kestrel, and Android points the settings
                // assistant and voice_interaction_service at that holder's service again (prototype P3).
                if (holder.isEmpty()) {
                    VoiceTests.shell("cmd role remove-role-holder $ROLE dev.kestrel")
                } else {
                    VoiceTests.shell("cmd role add-role-holder $ROLE $holder")
                }
                check(
                    VoiceTests.waitUntil(10_000) {
                        roleHolders() == holder && ASSISTANT_SETTINGS.all { unset(secure(it)) == unset(settings.getValue(it)) }
                    },
                ) { "The assistant role or its settings were not put back" }
            } catch (e: Throwable) {
                // The test's own failure comes first, with the failed restore added to it.
                val first = failure ?: throw e
                first.addSuppressed(e)
            }
        }
    }

    @Test
    fun theEarbudsAssistantButtonStartsListeningAndWaitsForATask() {
        // Started directly, as Android starts it once the owner picked Kestrel in its chooser (prototype P3).
        VoiceTests.shell("am start -n dev.kestrel/.app.assist.AssistActivity -a android.intent.action.VOICE_COMMAND")

        awaitTheWaitForATask()
        // The activity closes at once and leaves the launcher in front.
        assertTrue(
            "Kestrel's activity is still in front",
            VoiceTests.waitUntil(5_000) { !KESTREL_IN_FRONT.containsMatchIn(VoiceTests.shell("dumpsys activity activities")) },
        )
    }

    /** Waits until Kestrel listens, with its voice session open and the test's microphone recording, and waits for a task. */
    private fun awaitTheWaitForATask() {
        assertTrue(
            "Kestrel does not wait for a task; the state is ${listening.state.value}",
            VoiceTests.waitUntil(60_000) {
                val state = listening.state.value
                state.mode == Mode.LISTENING && state.work == Work.AWAITING_TASK && listening.sessionOpen.value && clip.recording
            },
        )
    }

    /** The holders of the assistant role, as `cmd role` prints them: a package name, or nothing. */
    private fun roleHolders(): String = VoiceTests.shell("cmd role get-role-holders $ROLE").trim()

    /** A secure setting as the shell prints it: "null" when it is not set. */
    private fun secure(name: String): String = VoiceTests.shell("settings get secure $name").trim()

    // Without an assistant, Android writes an empty value where the shell printed "null" before; both mean none.
    private fun unset(value: String): String = if (value == "null") "" else value

    private companion object {
        const val ROLE = "android.app.role.ASSISTANT"

        // The settings that name the assistant's service, which Android changes with the role (design D2).
        val ASSISTANT_SETTINGS = listOf("assistant", "voice_interaction_service")
        val FOCUSED_SESSION = Regex("""mCurrentFocus=Window\{[^}]*VoiceInteractionSession""")
        val KESTREL_IN_FRONT = Regex("""topResumedActivity=ActivityRecord\{[^}]* dev\.kestrel/""")
        val SETTINGS_IN_FRONT = Regex("""topResumedActivity=ActivityRecord\{[^}]* dev\.kestrel/\.app\.MainActivity""")
    }
}
