package dev.kestrel.app.voice

import android.app.Activity
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.MainActivity
import dev.kestrel.app.debug.EvalHooks
import dev.kestrel.app.debug.HookReply
import dev.kestrel.app.debug.TestHooks
import dev.kestrel.voice.models.Engine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The hooks of the voice corpus (design spec, section 16.4), called as TestHookReceiver calls them. */
class EvalHookTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val folder = File(context.getExternalFilesDir(null), EvalHooks.FOLDER)

    @After
    fun tearDown() {
        hook("VOICE_EVAL", flags = setOf("close"))
        hook("VOICE_SYNTH", flags = setOf("close"))
        File(folder, "gen-eval-test.wav").delete()
        File(folder, "eval-test.wav").delete()
    }

    @Test
    fun aClipInKestrelsVoiceIsTranscribedAndComparedWithoutItsText() {
        VoiceTests.assumeModels(Engine.VAD, Engine.ASR, Engine.TTS)
        val text = "Kestrel, wake up."

        // A generated clip's name starts with "gen-"; only such a clip may show what Kestrel heard.
        val wrote = hook("VOICE_SYNTH", "text" to text, "file" to "gen-eval-test")
        assertTrue(wrote.text, wrote.text.startsWith("wrote "))
        // Loading the recogniser takes several seconds, longer than a hook may run.
        val loaded = VoiceTests.waitUntil(120_000) { hook("VOICE_EVAL", flags = setOf("load")).text.startsWith("ready") }
        assertTrue("The recogniser did not load", loaded)
        assertTrue(LOADED.matches(hook("VOICE_EVAL", flags = setOf("load")).text))
        val extras = arrayOf("file" to "gen-eval-test", "expected" to text, "kind" to "WakeUp")
        val answer = hook("VOICE_EVAL", *extras, flags = setOf("spotted"))
        val revealed = hook("VOICE_EVAL", *extras, flags = setOf("spotted", "reveal"))

        assertEquals(answer.text, Activity.RESULT_OK, answer.code)
        // Whether the clip was heard and read right depends on the models, the boost words and the processor, and the
        // corpus measures it; this test checks only the hook's mechanics (review of plan 2D's Task 8, M1).
        assertTrue(answer.text, ANSWER.matches(answer.text))
        // The speech detector cuts off the clip's silence, as in Kestrel's pipeline, so one decoding is enough.
        assertTrue(answer.text, answer.text.endsWith(" parts=1 twice=0"))
        assertFalse(answer.text, answer.text.contains("heard="))
        assertTrue(revealed.text, revealed.text.contains(" heard="))
    }

    @Test
    fun whatKestrelHeardInAnOwnersClipIsNeverShown() {
        val extras = arrayOf("file" to "eval-test", "expected" to "Stop.", "kind" to "Stop")

        val reply = hook("VOICE_EVAL", *extras, flags = setOf("reveal"))

        val refusal = "error: only a generated clip, whose name starts with gen-, may show what Kestrel heard"
        assertEquals(HookReply(HookReply.FAILED, refusal), reply)
    }

    @Test
    fun aRecordingAnswersOnceItRecordsAndBecomesAClipOf16Kilohertz() {
        VoiceTests.grantListeningPermissions()

        // Android lets an app record only while it is visible or runs a foreground service of the type microphone.
        val result = ActivityScenario.launch(MainActivity::class.java).use {
            val started = hook("VOICE_RECORD", "file" to "eval-test", ints = mapOf("seconds" to 1))
            assertEquals(started.text, "recording", started.text)
            var reply = started
            val ended = VoiceTests.waitUntil(10_000) {
                reply = hook("VOICE_RECORD", flags = setOf("result"))
                reply.text != "recording"
            }
            assertTrue("The recording did not end", ended)
            reply
        }

        assertEquals(result.text, Activity.RESULT_OK, result.code)
        assertTrue(result.text, RECORDED.matches(result.text))
        // 44 bytes of header and two bytes a sample, for about a second at 16 kHz.
        val clip = File(folder, "eval-test.wav")
        assertTrue("${clip.length()} bytes", clip.length() in 44L + 2 * 12_000..44L + 2 * 20_000)
    }

    @Test
    fun aClipNameThatCouldLeaveTheFolderIsRefused() {
        val reply = hook("VOICE_SYNTH", "text" to "Stop.", "file" to "../escape")

        assertEquals(HookReply(HookReply.FAILED, "error: add --es file with a name of letters, digits, '-' and '_'"), reply)
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

    private companion object {
        val LOADED = Regex("ready words=\\d+ kept=\\d+")
        val ANSWER = Regex(
            "words=3 substitutions=\\d+ deletions=\\d+ insertions=\\d+ request=(ok|wrong) read=\\w+ millis=\\d+ " +
                "seconds=\\d+\\.\\d pss=\\d+ parts=\\d+ twice=\\d+",
        )
        val RECORDED = Regex("recorded \\d+\\.\\d s, loudest \\d+ %, silenced=(true|false), rms=(-?\\d+\\.\\d|none) frameRms=(-?\\d+\\.\\d|none)")
    }
}
