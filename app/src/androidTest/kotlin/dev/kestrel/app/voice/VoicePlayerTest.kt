package dev.kestrel.app.voice

import android.media.AudioAttributes
import android.media.AudioManager
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.MainActivity
import dev.kestrel.voice.VoiceOptions
import dev.kestrel.voice.models.Engine
import dev.kestrel.voice.speak.Spoke
import dev.kestrel.voice.speak.VoicePlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Kestrel's voice output on the emulator's speaker (design spec, section 8.4). */
@RunWith(AndroidJUnit4::class)
class VoicePlayerTest {
    @Before
    fun models() = VoiceTests.assumeModels(Engine.TTS)

    @Test
    fun speaksTheWholeReplySentenceBySentenceAsTheAssistant() = withPlayer { player ->
        val audio = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(AudioManager::class.java)
        val usages = mutableSetOf<Int>()

        val spoke = runBlocking {
            val reply = player.speak("Hello. This is Kestrel.")
            while (!reply.isCompleted) {
                audio.activePlaybackConfigurations.mapTo(usages) { it.audioAttributes.usage }
                delay(20)
            }
            reply.await()
        }
        val returned = SystemClock.elapsedRealtime()

        assertEquals(Spoke.FINISHED, spoke)
        assertTrue("Usages while speaking: $usages", AudioAttributes.USAGE_ASSISTANT in usages)
        // A reply without letters or digits never reaches the synthesizer, which would log its text, and plays nothing.
        assertEquals(Spoke.FINISHED, runBlocking { player.speak("… !?").await() })
        val spoken = player.recent()
        assertEquals(listOf("Hello.", "This is Kestrel."), spoken.map { it.text })
        assertTrue(spoken[0].endMillis > spoken[0].startMillis)
        assertTrue(spoken[1].startMillis >= spoken[0].endMillis - 50)
        val late = returned - spoken.last().endMillis
        VoiceTests.log("Voice output: finished $late ms after the estimated end")
        assertTrue("speak() returned $late ms after the reply ended", late < 700)
    }

    @Test
    fun stopEndsTheReplyAtOnce() = withPlayer { player ->
        runBlocking {
            val reply = player.speak("This is a long reply. It has several sentences. Each of them takes a while. Kestrel stops it at once.")
            delay(1_500)
            val stoppedAt = SystemClock.elapsedRealtime()
            player.stop()

            assertEquals(Spoke.STOPPED, reply.await())
            val millis = SystemClock.elapsedRealtime() - stoppedAt
            VoiceTests.log("Voice output: stopped in $millis ms")
            assertTrue("Stopping took $millis ms", millis < 500)
        }
    }

    @Test
    fun stopDuringTheSynthesisOfALongSentenceEndsTheReplyAtOnce() = withPlayer { player ->
        runBlocking {
            // One sentence of 184 characters, under the splitter's limit, which takes a while to synthesize.
            val sentence = "This sentence is long on purpose, so that Kestrel's voice needs a while to synthesize it, " +
                "and the player must not wait for that when the owner says stop before the first word is spoken"
            val reply = player.speak(sentence)
            delay(100)
            val stoppedAt = SystemClock.elapsedRealtime()
            player.stop()

            assertEquals(Spoke.STOPPED, reply.await())
            val millis = SystemClock.elapsedRealtime() - stoppedAt
            assertTrue("Stopping took $millis ms", millis < 300)
            // The next reply waits until the synthesizer is free, and then plays.
            assertEquals(Spoke.FINISHED, player.speak("Done.").await())
        }
    }

    @Test
    fun stopRightAfterSpeakEndsTheReplyBeforeItBegins() = withPlayer { player ->
        runBlocking {
            val reply = player.speak("Hello.")
            player.stop()

            assertEquals(Spoke.STOPPED, reply.await())
        }
    }

    @Test
    fun stopAlsoEndsAReplyThatWaitsForItsTurn() = withPlayer { player ->
        runBlocking {
            val first = player.speak("The first reply is a little longer than a moment.")
            delay(500)
            val second = player.speak("The second reply waits.")
            delay(200)
            player.stop()

            assertEquals(Spoke.STOPPED, first.await())
            assertEquals(Spoke.STOPPED, second.await())
        }
    }

    @Test
    fun aCancelledReplyDoesNotHoldUpTheNextOne() = withPlayer { player ->
        runBlocking {
            val reply = player.speak("The first sentence plays. The second one waits. The third one waits too.")
            delay(500)
            reply.cancel()

            assertEquals(Spoke.FINISHED, withTimeout(10_000) { player.speak("Done.").await() })
        }
    }

    private fun withPlayer(test: (VoicePlayer) -> Unit) {
        // Android 17 lets an app play audio only while it is visible or runs a suitable foreground service (B5).
        ActivityScenario.launch(MainActivity::class.java).use {
            // Without variation, the voice and its timing are the same in every run.
            val synthesizer = runBlocking { VoiceTests.graph.voice.synthesizer(VoiceOptions(variation = 0f)) }
            val player = VoicePlayer(InstrumentationRegistry.getInstrumentation().targetContext, synthesizer)
            try {
                test(player)
            } finally {
                runBlocking { player.close() }
                synthesizer.close()
            }
        }
    }
}
