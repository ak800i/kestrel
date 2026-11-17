package dev.kestrel.app.voice

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.MainActivity
import dev.kestrel.voice.Synthesizer
import dev.kestrel.voice.VoiceOptions
import dev.kestrel.voice.models.Engine
import dev.kestrel.voice.speak.Spoke
import dev.kestrel.voice.speak.VoicePlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Kestrel's voice output on the emulator's speaker (design spec, section 8.4). Some tests use a fake voice, so that they
 * need no model and can hold the voice in a call.
 */
@RunWith(AndroidJUnit4::class)
class VoicePlayerTest {
    @Test
    fun speaksTheWholeReplySentenceBySentenceAsTheAssistant() = withPlayer { player ->
        val audio = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(AudioManager::class.java)
        val usages = mutableSetOf<Int>()

        val spoke = runBlocking {
            val reply = player.speak("Hello. This is Kestrel.")
            withTimeout(10_000) {
                while (!reply.isCompleted) {
                    audio.activePlaybackConfigurations.mapTo(usages) { it.audioAttributes.usage }
                    delay(20)
                }
                reply.await()
            }
        }
        val returned = SystemClock.elapsedRealtime()

        assertEquals(Spoke.FINISHED, spoke)
        assertTrue("Usages while speaking: $usages", AudioAttributes.USAGE_ASSISTANT in usages)
        // A reply without letters or digits never reaches the synthesizer, which would log its text, and plays nothing.
        assertEquals(Spoke.FINISHED, runBlocking { withTimeout(10_000) { player.speak("… !?").await() } })
        val spoken = player.recent()
        assertEquals(listOf("Hello.", "This is Kestrel."), spoken.map { it.text })
        assertTrue(spoken[0].endMillis > spoken[0].startMillis)
        assertTrue(spoken[1].startMillis >= spoken[0].endMillis - 50)
        val late = returned - spoken.last().endMillis
        VoiceTests.log("Voice output: finished $late ms after the estimated end")
        assertTrue("speak() returned $late ms after the reply ended", late < 700)
        assertTrue("speak() returned $late ms before the reply ended", late > -30)
    }

    @Test
    fun stopEndsTheReplyAtOnce() = withPlayer { player ->
        runBlocking {
            val reply = player.speak("This is a long reply. It has several sentences. Each of them takes a while. Kestrel stops it at once.")
            delay(1_500)
            val stoppedAt = SystemClock.elapsedRealtime()
            player.stop()

            assertEquals(Spoke.STOPPED, withTimeout(10_000) { reply.await() })
            val millis = SystemClock.elapsedRealtime() - stoppedAt
            VoiceTests.log("Voice output: stopped in $millis ms")
            assertTrue("Stopping took $millis ms", millis < 500)
        }
    }

    @Test
    fun anotherAppsFocusStopsTheReplyAtOnceAndTheNextReplyAsksAgain() = withPlayer { player ->
        val audio = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(AudioManager::class.java)
        // Exclusive transient focus, as another assistant would ask for it; a request that lets others duck would only
        // make Kestrel quieter. Its own listener gives it a client ID of its own.
        val prompt = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { }
            .build()
        runBlocking {
            val reply = player.speak("This is a long reply. It has several sentences. Each of them takes a while. A prompt stops it.")
            awaitPlaying(player)
            val requestedAt = SystemClock.elapsedRealtime()
            try {
                assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audio.requestAudioFocus(prompt))
                // Section 14.6: the reply counts as interrupted, so that its text goes to the bar.
                assertEquals(Spoke.INTERRUPTED, withTimeout(10_000) { reply.await() })
                val millis = SystemClock.elapsedRealtime() - requestedAt
                VoiceTests.log("Voice output: another app's focus stopped the reply in $millis ms")
                assertTrue("Stopping took $millis ms", millis < 500)
            } finally {
                audio.abandonAudioFocusRequest(prompt)
            }
            assertEquals(Spoke.FINISHED, withTimeout(10_000) { player.speak("Done.").await() })
        }
    }

    @Test
    fun aSoundThatLetsKestrelDuckLeavesTheReplyPlaying() = withPlayer { player ->
        val audio = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(AudioManager::class.java)
        // A notification's sound, as Android's notification player asks for it, with a listener of its own.
        val sound = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setOnAudioFocusChangeListener { }
            .build()
        runBlocking {
            val reply = player.speak("This is a long reply. It has several sentences. A short sound comes while it plays.")
            awaitPlaying(player)
            try {
                assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audio.requestAudioFocus(sound))
                // Android does not lower the volume of speech by itself; it tells Kestrel, which lowers its own.
                withTimeout(2_000) { while (player.volume() == 1f) delay(20) }
                delay(500)
            } finally {
                audio.abandonAudioFocusRequest(sound)
            }
            // The focus came back, and Kestrel speaks at its full volume again.
            withTimeout(2_000) { while (player.volume() != 1f) delay(20) }
            // Section 8.4: Kestrel spoke on under the sound, more quietly, and finished the reply.
            assertEquals(Spoke.FINISHED, withTimeout(30_000) { reply.await() })
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

            assertEquals(Spoke.STOPPED, withTimeout(10_000) { reply.await() })
            val millis = SystemClock.elapsedRealtime() - stoppedAt
            assertTrue("Stopping took $millis ms", millis < 300)
            // The next reply waits until the synthesizer is free, and then plays.
            assertEquals(Spoke.FINISHED, withTimeout(10_000) { player.speak("Done.").await() })
        }
    }

    @Test
    fun stopRightAfterSpeakEndsTheReply() = withPlayer { player ->
        runBlocking {
            val reply = player.speak("Hello.")
            player.stop()

            assertEquals(Spoke.STOPPED, withTimeout(10_000) { reply.await() })
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

            assertEquals(Spoke.STOPPED, withTimeout(10_000) { first.await() })
            assertEquals(Spoke.STOPPED, withTimeout(10_000) { second.await() })
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

    @Test
    fun aSynthesisErrorEndsTheReplyWithItAndTheNextReplyPlays() {
        val voice = FakeVoice()
        withPlayer(voice) { player ->
            runBlocking {
                val failure = runCatching {
                    withTimeout(10_000) { player.speak("Hello. $FAILING Never said.").await() }
                }.exceptionOrNull()

                assertEquals(FakeVoice.FAILURE, failure?.message)
                assertEquals(Spoke.FINISHED, withTimeout(10_000) { player.speak("Done.").await() })
                assertEquals(listOf("Hello.", FAILING, "Done."), voice.said)
                assertEquals("Done.", player.recent().last().text)
            }
        }
    }

    @Test
    fun closeReturnsOnlyWhenTheSynthesisHasReturned() {
        val voice = FakeVoice(CountDownLatch(1))
        withPlayer(voice) { player ->
            runBlocking {
                val reply = player.speak("Hello.")
                assertTrue("The voice was not used", voice.started.await(5, TimeUnit.SECONDS))
                val closing = launch(Dispatchers.Default) { player.close() }
                delay(300)

                assertFalse("close() returned while the voice was in use", closing.isCompleted)
                voice.gate!!.countDown()
                withTimeout(5_000) { closing.join() }
                assertFalse("The voice is still in use", voice.busy)
                assertTrue(reply.isCompleted)
            }
        }
    }

    @Test
    fun aReplyAfterCloseIsStoppedWithoutTheVoice() {
        val voice = FakeVoice()
        withPlayer(voice) { player ->
            runBlocking {
                player.close()

                assertEquals(Spoke.STOPPED, withTimeout(10_000) { player.speak("Hello.").await() })
                assertEquals(emptyList<String>(), voice.said)
            }
        }
    }

    // Waits until the reply's first sentence plays, so that Kestrel holds the focus, however long its synthesis takes.
    private suspend fun awaitPlaying(player: VoicePlayer) {
        withTimeout(10_000) { while (player.recent().isEmpty()) delay(20) }
    }

    private fun withPlayer(test: (VoicePlayer) -> Unit) {
        VoiceTests.assumeModels(Engine.TTS)
        // Without variation, the voice and its timing are the same in every run.
        val synthesizer = runBlocking { VoiceTests.graph.voice.synthesizer(VoiceOptions(variation = 0f)) }
        synthesizer.use { withPlayer(it, test) }
    }

    private fun withPlayer(synthesizer: Synthesizer, test: (VoicePlayer) -> Unit) {
        // Android 17 lets an app play audio only while it is visible or runs a suitable foreground service (B5).
        ActivityScenario.launch(MainActivity::class.java).use {
            val player = VoicePlayer(InstrumentationRegistry.getInstrumentation().targetContext, synthesizer)
            try {
                test(player)
            } finally {
                runBlocking { player.close() }
            }
        }
    }

    /**
     * A voice without a model: it says every sentence as 0.3 s of silence and fails on [FAILING]. With a [gate], it waits
     * until the gate opens before it says anything.
     */
    private class FakeVoice(val gate: CountDownLatch? = null) : Synthesizer {
        val started = CountDownLatch(1)
        val said: MutableList<String> = Collections.synchronizedList(mutableListOf())

        @Volatile
        var busy = false

        override val sampleRate = 22_050

        override fun synthesize(text: String): FloatArray {
            busy = true
            try {
                said += text
                started.countDown()
                gate?.await(10, TimeUnit.SECONDS)
                if (text == FAILING) throw IllegalStateException(FAILURE)
                return FloatArray(sampleRate * 3 / 10)
            } finally {
                busy = false
            }
        }

        override fun close() = Unit

        companion object {
            const val FAILURE = "The fake voice failed"
        }
    }

    private companion object {
        const val FAILING = "This one fails."
    }
}
