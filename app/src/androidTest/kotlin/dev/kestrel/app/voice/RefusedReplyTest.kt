package dev.kestrel.app.voice

import dev.kestrel.app.agent.AgentTexts
import dev.kestrel.app.debug.ClipMicrophone
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.voice.models.Engine
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

/**
 * A reply that Android refuses to play: Kestrel shows its text on the feedback bar instead (design spec, section 14.6).
 * The test refuses Kestrel's audio focus with the app op TAKE_AUDIO_FOCUS, which Android 17 checks for every focus
 * request, and puts back the op's normal mode, foreground; the mode default would still refuse the focus
 * (prototype P2). It runs only on an emulator, where scripts\voice-test.ps1 also sets the op back to foreground at the
 * end, in case the test's process died before it could. It turns Kestrel's accessibility service on for the feedback
 * bar, and off again afterwards, as the voice tests after it expect.
 */
class RefusedReplyTest {
    // Before ScreenServiceRule: on a phone, the test is skipped before it changes anything there, and its @After does
    // not run either.
    @get:Rule(order = 0)
    val emulatorOnly = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                VoiceTests.assumeEmulator()
                base.evaluate()
            }
        }
    }

    @get:Rule(order = 1)
    val screen = ScreenServiceRule()

    private val listening = VoiceTests.graph.listening
    private val clip = ClipMicrophone()

    @Before
    fun setUp() {
        VoiceTests.assumeModels(Engine.VAD, Engine.KWS, Engine.ASR, Engine.TTS)
        VoiceTests.grantListeningPermissions()
    }

    @After
    fun tearDown() {
        try {
            VoiceTests.stopListening()
        } finally {
            VoiceTests.turnOffAccessibilityService()
        }
    }

    @Test
    fun aReplyThatAndroidRefusesIsShownOnTheBar() {
        // The accessibility service lets Kestrel start its microphone service from the background.
        VoiceTests.startListening(clip)
        try {
            VoiceTests.shell("cmd appops set dev.kestrel TAKE_AUDIO_FOCUS ignore")

            // A test build refuses a task without a test script at once (design D28 of sub-project 5), and Android now
            // refuses that reply.
            listening.send(Event.Heard("Kestrel, open the settings.", named = true))

            assertTrue(
                "The feedback bar did not show the refused reply",
                VoiceTests.waitUntil(10_000) { screen.session.feedbackText == AgentTexts.couldNotStart(AgentTexts.TEST_BUILD) },
            )
            assertTrue("The reply did not end", VoiceTests.waitUntil(5_000) { !listening.state.value.speaking })
        } finally {
            // Only here, after assumeEmulator() passed: foreground is the op's normal mode on Android 17, and default
            // would still refuse the focus (prototype P2).
            VoiceTests.shell("cmd appops set dev.kestrel TAKE_AUDIO_FOCUS foreground")
        }
    }
}
