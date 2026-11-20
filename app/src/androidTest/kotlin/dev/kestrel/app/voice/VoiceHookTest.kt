package dev.kestrel.app.voice

import android.app.Activity
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.MainActivity
import dev.kestrel.app.SettingKeys
import dev.kestrel.app.debug.ClipMicrophone
import dev.kestrel.app.debug.HookReply
import dev.kestrel.app.debug.TestHooks
import dev.kestrel.app.debug.VoiceHooks
import dev.kestrel.app.listen.ListeningSetting
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.voice.models.Engine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** The listening hooks of debug builds (design spec, section 16.1), called as TestHookReceiver calls them. */
class VoiceHookTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val listening = VoiceTests.graph.listening

    @After
    fun tearDown() {
        VoiceTests.stopListening()
    }

    @Test
    fun theAnswersAndTheStateCarryNoText() {
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

            // A check of the A71 sessions asks for its own command by its number, also after a newer one started.
            val own = listening.state.value.command + 1
            hook("VOICE", "event" to "heard", "text" to "Scroll down.")
            assertTrue("No end of command $own", VoiceTests.waitUntil(10_000) { listening.recentCommand(own)?.outcome != null })
            hook("VOICE", "event" to "heard", "text" to "Scroll up.")
            val ended = VoiceTests.waitUntil(10_000) { listening.recentCommand(own + 1)?.outcome != null }
            assertTrue("No end of command ${own + 1}", ended)
            val ownState = hook("VOICE_STATE", number = own).text
            val ownLine = Regex(".* command=$own kind=Scroll outcome=(done|failed|ignored|cancelled) latest=${own + 1} session=open clips=off")
            assertTrue(ownState, ownLine.matches(ownState))
            // The failed scrolls say so; the pause below expects a Kestrel that is quiet.
            assertTrue("Kestrel still speaks", VoiceTests.waitUntil(20_000) { !listening.state.value.speaking })

            hook("VOICE", "event" to "heard", "text" to "Stop listening.")
            assertTrue("Not paused", VoiceTests.waitUntil(10_000) { listening.state.value.mode == Mode.PAUSED })
            val state = hook("VOICE_STATE").text
            assertTrue(state, state.startsWith("mode=paused work=idle speaking=false microphone=true recognition=named"))

            // Without named, a paused Kestrel would take this for a request without its name, and stay paused.
            hook("VOICE", "event" to "heard", "text" to "Hey Kestrel, wake up.", named = true)
            assertTrue("Not listening again", VoiceTests.waitUntil(10_000) { listening.state.value.mode == Mode.LISTENING })

            assertEquals("sent off", hook("VOICE", "event" to "off").text)
            assertTrue("The voice session did not close", VoiceTests.waitUntil(30_000) { !listening.sessionOpen.value })
        }
    }

    @Test
    fun aTaggedTranscriptAnswersWhatItsOwnCommandDidAndATagIsUsedOnce() {
        VoiceTests.assumeModels(Engine.VAD, Engine.KWS, Engine.ASR, Engine.TTS)
        VoiceTests.grantListeningPermissions()
        VoiceTests.useMicrophone(ClipMicrophone())

        ActivityScenario.launch(MainActivity::class.java).use {
            assertEquals("sent toggle", hook("VOICE", "event" to "toggle").text)
            assertTrue("The voice session did not open", VoiceTests.waitUntil(60_000) { listening.sessionOpen.value })
            val tag = "own-${System.nanoTime()}"

            assertEquals("sent heard tag=$tag", hook("VOICE", "event" to "heard", "text" to "Scroll down.", "tag" to tag).text)
            // A transcript right after it, as a stray utterance, does not take the tag's command.
            hook("VOICE", "event" to "heard", "text" to "Scroll up.")
            val ended = VoiceTests.waitUntil(10_000) { hook("VOICE_STATE", "tag" to tag).text.contains(" phase=ended ") }
            assertTrue("The tagged command did not end", ended)
            val own = hook("VOICE_STATE", "tag" to tag).text
            val line = Regex(
                "tag=$tag role=command command=\\d+ task=0 kind=Scroll outcome=(done|failed|ignored|cancelled) asking=false phase=ended " +
                    "detail=none said=\\S+ shown=\\S+ read=other latest=\\d+",
            )
            assertTrue(own, line.matches(own))
            assertEquals(
                HookReply(HookReply.FAILED, "error: the tag $tag is in use; give each transcript a new tag"),
                hook("VOICE", "event" to "heard", "text" to "Scroll up.", "tag" to tag),
            )
            assertEquals(HookReply(HookReply.FAILED, "error: no transcript has the tag never-sent"), hook("VOICE_STATE", "tag" to "never-sent"))
            assertTrue("Kestrel still speaks", VoiceTests.waitUntil(20_000) { !listening.state.value.speaking })

            // A tagged transcript that starts a task gets the task's number, and its extras wait for the task's runner.
            val taskTag = "task-${System.nanoTime()}"
            val sent = hook("VOICE", "event" to "heard", "text" to "Kestrel, open the settings.", "tag" to taskTag, "model" to "test-model", named = true)
            assertEquals("sent heard tag=$taskTag", sent.text)
            assertTrue("The tagged task did not start", VoiceTests.waitUntil(10_000) { hook("VOICE_STATE", "tag" to taskTag).text.contains(" role=task ") })
            val taskLine = hook("VOICE_STATE", "tag" to taskTag).text
            val task = Regex(" task=(\\d+) ").find(taskLine)?.groupValues?.get(1)?.toInt() ?: 0
            assertTrue(taskLine, task > 0 && taskLine.contains(" command=0 ") && taskLine.contains(" read=task "))
            assertEquals(mapOf("model" to "test-model"), listening.taskOptions?.invoke(task))
            assertEquals(emptyMap<String, String>(), listening.taskOptions?.invoke(task))
            assertTrue("Kestrel still speaks", VoiceTests.waitUntil(20_000) { !listening.state.value.speaking })

            assertEquals("sent off", hook("VOICE", "event" to "off").text)
            assertTrue("The voice session did not close", VoiceTests.waitUntil(30_000) { !listening.sessionOpen.value })
        }
    }

    @Test
    fun quietMakesTheClipMicrophoneKestrelsAndItDeliversSilenceWithNothingQueued() {
        val previous = listening.microphones
        try {
            assertEquals(
                HookReply(
                    Activity.RESULT_OK,
                    "quiet: Kestrel hears only the silent clip microphone from its next start, until voice-clip -Clear or the end of its process",
                ),
                hook("VOICE_CLIP", quiet = true),
            )
            assertSame(VoiceHooks.microphone, listening.microphones())
            assertTrue(hook("VOICE_STATE").text.endsWith(" clips=on"))
            // Kestrel is off, so the test records itself: frames come every 20 ms, and all of them are silent.
            val frames = AtomicInteger()
            val sound = AtomicBoolean(false)
            VoiceHooks.microphone.start(onFrame = { frame -> frames.incrementAndGet(); if (frame.any { it != 0f }) sound.set(true) }, onError = {})
            try {
                Thread.sleep(1_000)
            } finally {
                VoiceHooks.microphone.stop()
            }
            assertTrue("Only ${frames.get()} frames came", frames.get() >= 25)
            assertFalse("The quiet microphone delivered sound", sound.get())

            assertEquals(
                "dropped the waiting clips; Kestrel uses its own microphone again from its next start",
                hook("VOICE_CLIP", clear = true).text,
            )
            assertSame(previous, listening.microphones)
        } finally {
            listening.microphones = previous
            VoiceHooks.microphone.clear()
        }
    }

    @Test
    fun anExpectedClipIsMatchedOnThePhoneAndRunsWithItsTagWhileAStrayIsDropped() {
        VoiceTests.assumeModels(Engine.VAD, Engine.KWS, Engine.ASR, Engine.TTS)
        VoiceTests.grantListeningPermissions()
        val previous = listening.microphones
        val tag = "air-${System.nanoTime()}"
        assertEquals(
            HookReply(HookReply.FAILED, "error: Kestrel's voice session is not open"),
            hook("VOICE_EXPECT", "text" to "Scroll down.", "tag" to tag),
        )
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                // Kestrel listens through the silent clip microphone, into which voice-clip says texts in Kestrel's voice.
                assertTrue(hook("VOICE_CLIP", quiet = true).text.startsWith("quiet: "))
                assertEquals("sent toggle", hook("VOICE", "event" to "toggle").text)
                assertTrue("The voice session did not open", VoiceTests.waitUntil(60_000) { listening.sessionOpen.value })

                assertEquals("expecting tag=$tag seconds=15", hook("VOICE_EXPECT", "text" to "Scroll down.", "tag" to tag).text)
                assertEquals(Activity.RESULT_OK, hook("VOICE_CLIP", "text" to "Hello there, how are you today?").code)
                assertEquals(Activity.RESULT_OK, hook("VOICE_CLIP", "text" to "Scroll down.").code)

                val done = VoiceTests.waitUntil(30_000) { !hook("VOICE_EXPECT", "tag" to tag, result = true).text.startsWith("waiting") }
                assertTrue("The expectation did not end", done)
                // The match may still wait in the Orchestrator's queue; its command shows once it has been handled.
                val handled = VoiceTests.waitUntil(10_000) { !hook("VOICE_STATE", "tag" to tag).text.contains(" role=pending ") }
                assertTrue("The Orchestrator did not handle the match", handled)
                val answer = hook("VOICE_EXPECT", "tag" to tag, result = true).text
                val line = Regex(
                    "tag=$tag matched=true words=2 errors=[01] stray=[12] split=0 speechEnded=(\\d+) closed=(\\d+) transcribed=(\\d+) " +
                        "run=true command=\\d+ kind=Scroll outcome=\\w+ right=-",
                )
                val found = line.matchEntire(answer)
                assertTrue(answer, found != null)
                val (ended, closed, transcribed) = found!!.destructured
                // The phone's own times: the speech ends, then the pause, then the text comes.
                assertTrue(answer, ended.toLong() in 1..closed.toLong() && closed.toLong() <= transcribed.toLong())
                assertTrue(hook("VOICE_STATE", "tag" to tag).text.startsWith("tag=$tag role=command "))
                assertTrue("Kestrel still speaks", VoiceTests.waitUntil(20_000) { !listening.state.value.speaking })
                assertEquals("sent off", hook("VOICE", "event" to "off").text)
                assertTrue("The voice session did not close", VoiceTests.waitUntil(30_000) { !listening.sessionOpen.value })
            }
        } finally {
            hook("VOICE_CLIP", clear = true)
            listening.microphones = previous
        }
    }

    @Test
    fun theStateAnswersForACommandThatHasNotStartedWithoutAKindOrAnOutcome() {
        val latest = listening.state.value.command

        val state = hook("VOICE_STATE", number = latest + 5).text

        assertTrue(state, state.contains(" command=${latest + 5} kind=none outcome=none latest=$latest session="))
        // Without a number, the answer is the latest command's, as before.
        val plain = hook("VOICE_STATE").text
        assertTrue(plain, STATE_WHILE_OFF.matches(plain))
    }

    @Test
    fun aClipWaitsForTheNextStartInTheHooksMicrophone() {
        VoiceTests.assumeModels(Engine.TTS)
        val previous = listening.microphones
        try {
            val reply = hook("VOICE_CLIP", "text" to "Kestrel.")

            assertEquals(reply.text, Activity.RESULT_OK, reply.code)
            assertTrue(reply.text, reply.text.contains("from Kestrel's next start"))
            assertSame(VoiceHooks.microphone, listening.microphones())
            val state = hook("VOICE_STATE").text
            assertTrue(state, state.endsWith(" clips=on"))
            // Kestrel is off, so the test can start the microphone itself. The speech starts 0.5 s into the clip.
            assertTrue("The clip microphone delivered no speech", heardWithin(5_000))
        } finally {
            listening.microphones = previous
            // The clip would otherwise wait in the process-wide microphone for a later hook's start.
            VoiceHooks.microphone.clear()
        }
    }

    @Test
    fun clearDropsTheClipsAndPutsBackTheMicrophoneThatTheClipReplaced() {
        VoiceTests.assumeModels(Engine.TTS)
        val previous = listening.microphones
        try {
            assertEquals(Activity.RESULT_OK, hook("VOICE_CLIP", "text" to "Kestrel.").code)

            val reply = hook("VOICE_CLIP", clear = true)

            assertEquals(
                HookReply(Activity.RESULT_OK, "dropped the waiting clips; Kestrel uses its own microphone again from its next start"),
                reply,
            )
            assertSame(previous, listening.microphones)
            val state = hook("VOICE_STATE").text
            assertTrue(state, state.endsWith(" clips=off"))
            // The clip's speech would start 0.5 s in.
            assertFalse("The clip microphone still delivered speech", heardWithin(1_500))
        } finally {
            listening.microphones = previous
            VoiceHooks.microphone.clear()
        }
    }

    @Test
    fun aBlankClipIsRefused() {
        val previous = listening.microphones
        try {
            val reply = hook("VOICE_CLIP", "text" to " ")

            assertEquals(HookReply(HookReply.FAILED, "error: add a text that is not blank"), reply)
            assertSame(previous, listening.microphones)
        } finally {
            listening.microphones = previous
            VoiceHooks.microphone.clear()
        }
    }

    @Test
    fun anUnknownEventSaysWhichEventsExist() {
        val reply = hook("VOICE", "event" to "shout")

        assertEquals(HookReply.FAILED, reply.code)
        assertTrue(reply.text, reply.text.startsWith("error: add --es event with one of toggle"))
    }

    @Test
    fun offStoresOffAlsoWhileKestrelIsOff() {
        val settings = VoiceTests.graph.settings
        try {
            // As an interrupted run leaves it: Kestrel is off in this process, and Listening is stored.
            runBlocking { settings.set(SettingKeys.LISTENING, ListeningSetting(Mode.LISTENING)) }
            assertEquals(Mode.OFF, listening.state.value.mode)

            assertEquals("sent off", hook("VOICE", "event" to "off").text)

            assertEquals(Mode.OFF, runBlocking { settings.get(SettingKeys.LISTENING) }.mode)
        } finally {
            runBlocking { settings.set(SettingKeys.LISTENING, ListeningSetting()) }
        }
    }

    /**
     * Runs a hook command as TestHookReceiver does, with string extras, the boolean extras `named`, `clear`, `quiet` and
     * `result` and the integer extra `number`.
     */
    private fun hook(
        command: String,
        vararg extras: Pair<String, String>,
        named: Boolean = false,
        clear: Boolean = false,
        number: Int? = null,
        quiet: Boolean = false,
        result: Boolean = false,
    ): HookReply {
        val intent = Intent(TestHooks.ACTION_PREFIX + command)
        for ((key, value) in extras) intent.putExtra(key, value)
        if (number != null) intent.putExtra("number", number)
        if (named) intent.putExtra("named", true)
        if (clear) intent.putExtra("clear", true)
        if (quiet) intent.putExtra("quiet", true)
        if (result) intent.putExtra("result", true)
        return runBlocking { TestHooks(VoiceTests.graph, context).run(intent) }
    }

    /** Records with the hooks' microphone for up to [timeoutMillis], and returns whether a frame was not silent. */
    private fun heardWithin(timeoutMillis: Long): Boolean {
        val heard = AtomicBoolean(false)
        VoiceHooks.microphone.start(onFrame = { frame -> if (frame.any { it != 0f }) heard.set(true) }, onError = {})
        try {
            return VoiceTests.waitUntil(timeoutMillis) { heard.get() }
        } finally {
            VoiceHooks.microphone.stop()
        }
    }

    private companion object {
        val STATE_WHILE_OFF = Regex(
            "mode=off work=idle speaking=false microphone=false recognition=named screenOn=(true|false) micBusy=false " +
                "command=\\d+ kind=\\w+ outcome=\\w+ session=closed clips=off",
        )
    }
}
