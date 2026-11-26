package dev.kestrel.app.voice

import android.Manifest
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.debug.ClipMicrophone
import dev.kestrel.app.listen.SettingsOpener
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.app.orchestrator.Work
import dev.kestrel.voice.models.Engine
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

/**
 * The assistant gesture and the earbuds' assistant button (design spec, section 8.6; prototype P3), with Kestrel in the
 * background and its accessibility service off. Both start listening and wait for a task. With the assistant role,
 * "Kestrel, open settings" also brings Kestrel's settings to the front. The two tests of the role run only on an
 * emulator, because they make Kestrel the holder of the role: they put the holder back themselves, and
 * scripts\voice-test.ps1 puts it back at the end in case the test's process died before it could. They also grant
 * Kestrel the role's permissions, which only voice-test.ps1 puts back.
 */
class AssistTest {
    // The tests of the role skip on a phone before setUp changes anything there.
    @get:Rule
    val roleOnlyOnAnEmulator = TestRule { base, description ->
        object : Statement() {
            override fun evaluate() {
                if (description.methodName in ROLE_TESTS) VoiceTests.assumeEmulator()
                base.evaluate()
            }
        }
    }

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
        // It changes the holder of the assistant role, which only voice-test.ps1 puts back if the test's process dies, so
        // it is in ROLE_TESTS.
        asTheAssistant {
            VoiceTests.shell("input keyevent KEYCODE_ASSIST")

            awaitTheWaitForATask()
            assertNoSessionWindow()
        }
    }

    @Test
    fun withTheAssistantRoleOpenSettingsBringsTheSettingsToTheFront() {
        // It changes the holder of the assistant role, as the test of the assist key does, so it is in ROLE_TESTS.
        asTheAssistant {
            // The role alone lets Kestrel start its microphone service from the background (prototype P3).
            VoiceTests.startListening(clip)
            try {
                // The setup's Home key returns before the launcher is in front, and an earlier test may have left the
                // settings there, so the test waits until they are not in front.
                assertTrue(
                    "Kestrel's settings are in front before the command",
                    VoiceTests.waitUntil(5_000) {
                        !SETTINGS_IN_FRONT.containsMatchIn(VoiceTests.shell("dumpsys activity activities"))
                    },
                )
                val resumes = VoiceTests.graph.timeline.settingsResumes.value
                val askedBefore = VoiceTests.graph.timeline.settingsAsked
                listening.send(Event.Heard("Kestrel, open settings", named = true))

                // Without the role and the accessibility service, Android would block the start without an exception.
                // With only the role, Kestrel starts the settings through its assistant's session (A71 session of
                // sub-project 2, item 5), and MainActivity's own resume tells Kestrel and the A71 session that they came.
                assertTrue(
                    "Kestrel's settings did not come to the front",
                    VoiceTests.waitUntil(10_000) {
                        VoiceTests.graph.timeline.settingsResumes.value > resumes &&
                            SETTINGS_IN_FRONT.containsMatchIn(VoiceTests.shell("dumpsys activity activities"))
                    },
                )
                // Kestrel gives up 5 s after it asked for its settings (SettingsOpener) and says so; settings that came
                // later must not hide that verdict. Timeline's own times of this run, which the hook voice-times reads,
                // show it, while the latest reaction can be an older one or be replaced by a newer one.
                val asked = VoiceTests.graph.timeline.settingsAsked
                val resumed = VoiceTests.graph.timeline.settingsResumes.value
                assertTrue("Kestrel did not ask for its settings in this run", asked > askedBefore)
                assertTrue(
                    "Kestrel's settings came ${resumed - asked} ms after Kestrel asked for them, after its wait",
                    resumed - asked < SettingsOpener.WAIT_MILLIS,
                )
                assertNoSessionWindow()
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
            // The role grants its holder the permissions of its list that the holder declares, and revokes them when the
            // role goes back, which ends Kestrel's process, the test's own (investigation of plan 5B). A permission that
            // is granted already stays granted when the role goes back. The test cannot revoke them, as that would end
            // its process too, so scripts\voice-test.ps1 puts them back at the end.
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            for (permission in ROLE_PERMISSIONS) automation.grantRuntimePermission("dev.kestrel", permission)
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

    /**
     * Kestrel's session has shown and has no window. A window would cover the app in front and take the focus (prototype
     * P3), and a change of its focus that Kestrel's main thread cannot take within 5 s, as while it starts the settings,
     * is an ANR that ends Kestrel's process. While Kestrel holds the role, only its session can have such a window.
     */
    private fun assertNoSessionWindow() {
        assertFalse(
            "The assistant session has a window",
            SESSION_WINDOW.containsMatchIn(VoiceTests.shell("dumpsys window windows")),
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

        // The permissions of the role's list (its permission set "sms" and READ_CALL_LOG) that Kestrel declares, as
        // voice-test.ps1 knows.
        val ROLE_PERMISSIONS = listOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CALL_LOG)

        // The tests that make Kestrel the holder of the role, which run only on an emulator, as voice-test.ps1 knows.
        val ROLE_TESTS = setOf(
            "theAssistKeyStartsListeningAndWaitsForATask",
            "withTheAssistantRoleOpenSettingsBringsTheSettingsToTheFront",
        )

        // The settings that name the assistant's service, which Android changes with the role (design D2).
        val ASSISTANT_SETTINGS = listOf("assistant", "voice_interaction_service")
        val SESSION_WINDOW = Regex("""Window\{[0-9a-f]+ u\d+ VoiceInteractionSession\}""")
        // Android 13 prints "  ResumedActivity: ActivityRecord{…}", later versions "topResumedActivity=ActivityRecord{…}".
        val KESTREL_IN_FRONT = Regex("""(?:topResumedActivity=|\bResumedActivity: )ActivityRecord\{[^}]* dev\.kestrel/""")
        val SETTINGS_IN_FRONT =
            Regex("""(?:topResumedActivity=|\bResumedActivity: )ActivityRecord\{[^}]* dev\.kestrel/\.app\.MainActivity""")
    }
}
