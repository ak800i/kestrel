package dev.kestrel.app.voice

import android.app.Activity
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.MainActivity
import dev.kestrel.app.debug.ClipMicrophone
import dev.kestrel.app.debug.HookReply
import dev.kestrel.app.debug.TestHooks
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Work
import dev.kestrel.voice.models.Engine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hooks of the A71 session (plan 2D, design D8), called as TestHookReceiver calls them. */
class SessionHookTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val listening = VoiceTests.graph.listening

    @After
    fun tearDown() {
        hook("VOICE_TASK", flags = setOf("clear"))
        VoiceTests.stopListening()
    }

    @Test
    fun aSlowTaskRunsForItsTimeIsCountedAndClearBringsBackTheTaskThatEndsAtOnce() {
        VoiceTests.assumeModels(Engine.VAD, Engine.KWS, Engine.ASR, Engine.TTS)
        VoiceTests.grantListeningPermissions()
        val stub = listening.runTask

        // Kestrel is in front, so that Android lets it start its foreground service of the type microphone.
        ActivityScenario.launch(MainActivity::class.java).use {
            VoiceTests.startListening(ClipMicrophone())
            val reply = hook("VOICE_TASK", "reply" to "The task is done.", ints = mapOf("seconds" to 3))
            assertEquals(reply.text, Activity.RESULT_OK, reply.code)
            assertTrue(reply.text, reply.text.startsWith("each task now takes 3 s and then says a reply of 17 characters"))
            val tasks = listening.state.value.task

            listening.send(Event.Heard("Kestrel, open the window.", named = true))

            assertTrue("No task ran", VoiceTests.waitUntil(5_000) { listening.state.value.work == Work.RUNNING_TASK })
            // The counts name the waits, the tasks, the words of the last task and the keyword hits, never the words
            // themselves.
            val counts = hook("VOICE_COUNTS").text
            assertTrue(counts, Regex("waits=\\d+ tasks=${tasks + 1} lastTaskWords=3 hits=\\d+").matches(counts))
            // The task is still running a second later, unlike the task that ends at once.
            Thread.sleep(1_000)
            assertEquals(Work.RUNNING_TASK, listening.state.value.work)
            assertTrue("The task did not end", VoiceTests.waitUntil(10_000) { listening.state.value.work == Work.IDLE })
        }
        assertEquals("each task ends at once again", hook("VOICE_TASK", flags = setOf("clear")).text)
        assertSame(stub, listening.runTask)
    }

    @Test
    fun theEchoCheckPlaysTheTextInTheBackgroundAndAnswersOnlyCounts() {
        VoiceTests.assumeModels(Engine.KWS, Engine.TTS)
        VoiceTests.grantListeningPermissions()

        // Android lets an app record and play only while it is visible or runs a suitable foreground service.
        val result = ActivityScenario.launch(MainActivity::class.java).use {
            val started = hook("VOICE_ECHO", "text" to "Should I stop the timer?")
            assertEquals(started.text, "playing", started.text)
            var reply = started
            val ended = VoiceTests.waitUntil(60_000) {
                reply = hook("VOICE_ECHO", flags = setOf("result"))
                reply.text != "playing"
            }
            assertTrue("The echo check did not end", ended)
            reply
        }

        assertEquals(result.text, Activity.RESULT_OK, result.code)
        // Kestrel is in front, so Android gives it the audio focus, and the reply plays to its end. The emulator's
        // microphone hears silence, so the numbers of hits and the loudest level may be 0.
        val answer = Regex("result=finished hits=\\d+ own=\\d+ loudest=\\d+ silenced=(true|false)")
        assertTrue(result.text, answer.matches(result.text))
    }

    /** Runs a hook command as TestHookReceiver does, with string extras, int extras and boolean extras that are true. */
    private fun hook(
        command: String,
        vararg extras: Pair<String, String>,
        ints: Map<String, Int> = emptyMap(),
        flags: Set<String> = emptySet(),
    ): HookReply {
        val intent = Intent(TestHooks.ACTION_PREFIX + command)
        for ((key, value) in extras) intent.putExtra(key, value)
        for ((key, value) in ints) intent.putExtra(key, value)
        for (flag in flags) intent.putExtra(flag, true)
        return runBlocking { TestHooks(VoiceTests.graph, context).run(intent) }
    }
}
