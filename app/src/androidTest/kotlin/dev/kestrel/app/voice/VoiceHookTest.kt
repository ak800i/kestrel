package dev.kestrel.app.voice

import android.app.Activity
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.MainActivity
import dev.kestrel.app.debug.ClipMicrophone
import dev.kestrel.app.debug.HookReply
import dev.kestrel.app.debug.TestHooks
import dev.kestrel.app.debug.VoiceHooks
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.voice.models.Engine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The listening hooks of debug builds (design spec, section 16.1), called as TestHookReceiver calls them. */
class VoiceHookTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val listening = VoiceTests.graph.listening

    @After
    fun tearDown() {
        VoiceTests.stopListening()
    }

    @Test
    fun theStateHasNoTextAndATranscriptWhileOffChangesNothing() {
        assertEquals(Mode.OFF, listening.state.value.mode)

        val sent = hook("VOICE", "event" to "heard", "text" to "Kestrel, open the settings.", named = true)
        val state = hook("VOICE_STATE")

        assertEquals(HookReply(Activity.RESULT_OK, "sent heard"), sent)
        assertTrue(state.text, STATE_WHILE_OFF.matches(state.text))
    }

    @Test
    fun theHooksTurnListeningOnPauseItAndTurnItOff() {
        VoiceTests.assumeModels(Engine.VAD, Engine.KWS, Engine.ASR, Engine.TTS)
        VoiceTests.grantListeningPermissions()
        VoiceTests.useMicrophone(ClipMicrophone())

        // Kestrel is in front, so that Android lets the hook's start begin a foreground service of the type microphone.
        ActivityScenario.launch(MainActivity::class.java).use {
            assertEquals("sent toggle", hook("VOICE", "event" to "toggle").text)
            assertTrue("The voice session did not open", VoiceTests.waitUntil(60_000) { listening.sessionOpen.value })

            hook("VOICE", "event" to "heard", "text" to "Stop listening.")
            assertTrue("Not paused", VoiceTests.waitUntil(10_000) { listening.state.value.mode == Mode.PAUSED })
            val state = hook("VOICE_STATE").text
            assertTrue(state, state.startsWith("mode=paused work=idle speaking=false microphone=true recognition=named"))

            assertEquals("sent off", hook("VOICE", "event" to "off").text)
            assertTrue("The voice session did not close", VoiceTests.waitUntil(30_000) { !listening.sessionOpen.value })
        }
    }

    @Test
    fun aClipWaitsForTheNextStartInTheHooksMicrophone() {
        VoiceTests.assumeModels(Engine.TTS)
        val previous = listening.microphones
        try {
            val reply = hook("VOICE_CLIP", "text" to "Kestrel.")

            assertEquals(reply.text, Activity.RESULT_OK, reply.code)
            assertTrue(reply.text, reply.text.contains("from its next start"))
            assertSame(VoiceHooks.microphone, listening.microphones())
        } finally {
            listening.microphones = previous
            // The clip would otherwise wait in the process-wide microphone for a later hook's start.
            VoiceHooks.microphone.clear()
        }
    }

    @Test
    fun anUnknownEventSaysWhichEventsExist() {
        val reply = hook("VOICE", "event" to "shout")

        assertEquals(HookReply.FAILED, reply.code)
        assertTrue(reply.text, reply.text.startsWith("error: add --es event with one of toggle"))
    }

    /** Runs a hook command as TestHookReceiver does, with string extras and the boolean extra `named`. */
    private fun hook(command: String, vararg extras: Pair<String, String>, named: Boolean = false): HookReply {
        val intent = Intent(TestHooks.ACTION_PREFIX + command)
        for ((key, value) in extras) intent.putExtra(key, value)
        if (named) intent.putExtra("named", true)
        return runBlocking { TestHooks(VoiceTests.graph, context).run(intent) }
    }

    private companion object {
        val STATE_WHILE_OFF = Regex(
            "mode=off work=idle speaking=false microphone=false recognition=named screenOn=(true|false) micBusy=false session=closed",
        )
    }
}
